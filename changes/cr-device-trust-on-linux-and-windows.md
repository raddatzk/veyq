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
  - the web behaves as before; Android and the Apple app behave as before until Phase 8, which changes their trust offer as D7 says (owner 2026-11-11); `./gradlew :core:jvmTest :core:macosArm64Test` and both desktops' `run.py test` stay green in every phase
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

### D7 — The person chooses, after the sign-in, among what the device offers (owner 2026-11-11; supersedes D5's switch and Q1+Q2's "strongest the device has")

The owner changed the enrolment for **all four apps** (the web keeps its checkbox: it has no presence level):
- **The sign-in has no "Stay signed in" switch.** Email, Secret Key and password; every sign-in then holds the password
  trust (`DEVICE`), as a sign-in without the switch does today, so this device never asks for the Secret Key again.
- **Right after it, an offer** of what this device can do, as banking and password-manager apps ask after the first
  sign-in:
  - **"Sign in with Windows Hello"** (Face ID, Touch ID, fingerprint — the platform's word), where the store has a
    presence check: a `DEVICE_KEY` whose unlock key opens only after the person authenticated;
  - **"Stay signed in"**: a `DEVICE_KEY` whose unlock key opens without asking, the device's own sign-in the
    authentication (Windows DPAPI, the Secret Service, Android Keystore with no user authentication, a Keychain item
    without `.userPresence`). **Offered beside the presence level, not only where it is missing:** a device with a
    fingerprint reader must not force anyone to use it (owner 2026-11-11);
  - **"Not now"**: the password trust stays; every start asks for the veyq password.
  - With no secure store (`NONE`) there is no offer.
- **Later:** Manage servers changes it per server (from either level to the other, or back to the password trust).
- **One `DEVICE_KEY` wrap per device stays** (ADR 0005 §9): choosing the other level replaces the wrap and its key.
- **Asked once per account and device:** "Not now" is remembered in the device store (not secret), so the offer does
  not return at every sign-in; Manage servers stays the way to it.

What it takes:
- **The core:**
  - `DeviceKeys` seals an unlock key **per level** (`sealUnlockable(…, presence: Boolean)`). The level is kept beside
    the key, as Windows' `hello`/`dpapi` path already is, so an open uses the level its seal used.
  - `StaySignedIn` becomes **the set of levels the store offers** (`PRESENCE`, `LOGIN`, both, or none).
  - `SessionModel` gains the action "stay signed in now, at level X", taken while signed in (the Master Key is held), and
    the "asked" mark.
  - Account's enrolment moves from the sign-in to that action; the sign-in's `remember` goes.
  - Android's and Apple's `DeviceKeys` gain the level without a check: a second Keystore AES key without
    `setUserAuthenticationRequired`, a Keychain item without `.userPresence`.
- **ADR 0123 decision 8 changes:** the unlock key opens after authentication **at the presence level**; the level without
  a check is the person's choice. The security model (D6) writes both levels per platform.
- **The screens:** the desktops' switch from Phases 4 and 5 is replaced by the offer; Android and Apple gain it in place of
  their switch.

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

**Built (Phase 2):**
- **The cinterop `secret`** (`core/secret/secret.def`, `veyq_secret.h`), for `linuxArm64` and `linuxX64`: C helpers
  `dlopen("libsecret-1.so.0")` and `dlsym` what they call; only integers and UTF-8 strings cross. The header declares
  GError's leading fields and `SecretSchema` whole itself; no glib or libsecret header. **Deviation from D2's ABI list:**
  the variadic `secret_password_store_sync`/`lookup_sync`/`clear_sync` (attributes as a NULL-terminated list) instead of
  the `…v_sync` ones, so no `g_hash_table_*`; and `secret_service_get_sync` with `OPEN_SESSION` plus `g_object_unref`
  for the capability. `dlopen` is libdl's in Kotlin/Native's sysroot (glibc 2.19 x86-64, 2.25 arm64); its Linux
  link flags carry `-ldl` already, and `secret.def` names it. The header is the cinterop task's input.
- **`LinuxDeviceKeys`** (`linuxMain/…/session/LinuxDeviceKeys.kt`), as D2 says: items `bind/<alias>`, `secret/<alias>`
  and `unlock` (labels "veyq device key", "veyq sign-in token", "veyq unlock key"), base64 where bytes; no prompt, so
  never `cancelled`. Absent library, no Secret Service, a refused store: creation and `putSecret` raise
  `DeviceAuthFailed(false)`, a read gives null, a delete does nothing (it never fails sign-out or stop trusting).
- **The capability `secretServiceAvailable`:** libsecret loaded and a session opened with the service (no item read,
  so a locked collection is not asked to open). **Phase 3 hides the switch where it is false.** Android and Apple show
  it without a screen lock because the enrolment still falls back to the password trust; on Linux without a Secret
  Service the bind key cannot be kept either, so the switch would do nothing. A locked collection the person does not
  open fails the enrolment, which falls back as everywhere.
- **The tests (`linuxTest`):** `LinuxDeviceKeysContractTest` (D3 over the real service, each store in a namespace of
  its own), `LinuxDeviceKeysTest` (an absent soname, namespaces apart, a key outliving its object, the service
  answers) and `SecretServiceProbeTest` (the app's own `LinuxDeviceKeys()`, held to `VEYQ_SECRET_SERVICE`).
- **`run.py test`:** the image gains `libsecret gnome-keyring dbus-daemon`; the binary runs under `dbus-run-session` with
  `gnome-keyring-daemon --unlock`, fed `head -c 32 /dev/urandom | base64` inside the container (never printed); the
  container gets `--cap-add=IPC_LOCK` (the daemon aborts as root without it). Without `--tests`, the probe runs again
  with no session bus (`no-bus`) and with `libsecret-1.so.0*` moved away (`absent`).

**Met (2026-11-11, commits `9c5f14630`, `76913341e`):**
- [x] `/usr/bin/python3 src/mobile/linux/run.py test` → `PASSED 199` (189 before), the contract over gnome-keyring
  included; then `no-bus` → `Secret Service available: false`, `PASSED 1`; then `absent` → `libsecret absent`,
  `PASSED 1`; exit 0. The items are the service's: `secret-tool search --all xdg:schema me.raddatz.veyq.DeviceKey`
  in the container listed the bind key, unlock key and token under `attribute.alias = test.<ns>/…`, in `login.keyring`.
- [x] Absent library: enrol's `sealUnlockable` and `createBindKey` answer `DeviceAuthFailed(cancelled=false)`, reads
  null, deletes quiet, the capability false — in-process (an absent soname) and with the real library moved away.
- [x] `/usr/bin/python3 src/mobile/linux/run.py bridge` → the export check and the smoke green. `readelf -d
  libveyq.so` (release, arm64): NEEDED is libresolv, libm, libpthread, libutil, libcrypt, librt, libz, libdl,
  libgcc_s, libc, ld-linux — no libsecret (libdl was Kotlin/Native's default `-ldl` already). The bridge does not call
  `LinuxDeviceKeys` until Phase 3, so the same was read of `test.kexe`, which does: 11 NEEDED, none libsecret.
- [x] Unchanged: `./gradlew :core:jvmTest --rerun :core:macosArm64Test --rerun` → `BUILD SUCCESSFUL`;
  `:core:linkDebugTestLinuxX64 :core:compileLinuxMainKotlinMetadata --warning-mode all` → green, no deprecation;
  `/usr/bin/python3 src/mobile/windows/run.py test` → `PASSED 200`. No dependency added, so no verification-metadata write.
- [x] **Bites**, each red then restored: the C store ignoring the alias (3 contract tests red); the absent path
  throwing `IllegalStateException` instead of `DeviceAuthFailed(false)` (`withoutLibsecret…` and the probe red); the
  namespace ignored (`…KeepsNothingOfAnotherNamespace` and the contract red); `deleteUnlockKey` a no-op alone
  (`anOpenWithoutAnUnlockKeyFailsAsNotCancelled` red); the capability answering true without a service (the `no-bus`
  probe red, `run.py test` exit 1).

### Phase 3 — The bridge

D4: the calls, `Config.window`, `platformDeviceKeys`, the generated Rust and C#.

**Done when:**
- `./gradlew :bridge:jvmTest` is green: `DispatchTest` covers each new call over the played door, with
  `SoftwareDeviceKeys`. `SecretFreeSchemaTest` holds, with its bite.
- `LiveBridgeTest` signs in with `remember` over `SoftwareDeviceKeys`, resumes and unlocks.
- `DeviceTrustLiveTest` runs over the contract (D3) and is still green.
- Both `run.py bridge` are green.

**Built (Phase 3):**
- **The core's answer, `StaySignedIn`** (`session/DeviceTrust.kt`): `PRESENCE` (the store makes the person confirm:
  Windows Hello's key, Android's screen lock, Apple's `.userPresence`; Windows labels it "with Windows Hello"), `LOGIN`
  (the desktop sign-in opens it: DPAPI without Hello, the Secret Service), `NONE` (nothing staying signed in needs can be
  kept: the switch is hidden). `DeviceKeys.staySignedIn` asks each store: Windows `helloAvailable` → PRESENCE else LOGIN,
  Linux `secretServiceAvailable` → LOGIN else NONE, Android `KeyguardManager.isDeviceSecure` and Apple
  `LAContext.canEvaluatePolicy(DeviceOwnerAuthentication)` → PRESENCE else NONE (neither app reads it yet).
  `SessionModel.staySignedIn` asks it through the door.
- **The refresh rule** (`Account.kt`, `keepRefresh`): a rotated refresh token the store fails to keep is logged, never
  thrown — at an API call's refresh after a 401 and at the resume alike (the refresh succeeded both times; failing the
  resume would have lost this start as well as the next). The next start's resume then finds the old token refused
  (SESSION_ENDED). Held by `RefreshKeptTest` (`commonTest`, over a server MockEngine plays and a `SoftwareDeviceKeys`
  whose `putSecret` throws).
- **The wire** (`Wire.kt`): `Session.SignIn.remember`, `Session.Unlock(password)`, `Session.OtherAccount`,
  `Session.ForgetDevice(connection)`, `Session.AddServer`, `Session.CancelAdd`, `Manage.Open`/`Manage.Close`;
  `Watched.Manage` → `Update.Manage(servers)` (null while closed: each server's connection, label, `trusted` method and
  tenants as `SessionModel.managed` has them, each tenant's spaces with their names as they read); `Update.Session` gains
  `staySignedIn`, asked of the store only beside a `Gate.SignIn` (null beside the others: the probe may reach D-Bus or
  cryptngc). `Config` gains `window` (HWND as a number) and `prompt` (Hello's words, the host's `device_unlock_prompt`).
- **`Bridge.live`** takes the keys over the host's store: `platformDeviceKeys(config, store)`, an `expect` in
  `bridgedMain` (`LinuxDeviceKeys()`; `WindowsDeviceKeys(store, window, prompt)`); a JVM test hands its own.
- **`SoftwareDeviceKeys`** moved to `core/src/testDevice/`, compiled into core's `commonTest` and `:bridge`'s `jvmTest`,
  so the bridge's tests run over the same fake.
- **The hosts:** Rust and C# regenerated; the call sites send `remember: false` (the switch is Phases 4 and 5's), Windows'
  `MainWindow` sends its HWND and the prompt's words, Linux `0` and `""`; `smoke.c`'s sign-ins carry `remember`.

**Met (2026-11-11, commits `a22a727cb`, `a7274d33b`):**
- [x] `./gradlew :bridge:jvmTest` → green, `DispatchTest` 12 (4 new, over a door whose trust goes through
  `SoftwareDeviceKeys`: remember off then the password unlock; remember on, a resume, a cancelled prompt staying on the
  unlock, the device key's unlock; "Another account", "Add server", cancel, and the switch's answer following the keys;
  Manage servers with each server's trust, Stop trusting, close), `SecretFreeSchemaTest` with `Session.Unlock.password`
  let through as typed inward. **Bites**, each red then restored: that allowance dropped (`whatTheHostSends…` red);
  `remember` hard-coded false (two red); the switch's answer hard-coded PRESENCE (one red); Manage not refreshed after
  Stop trusting (one red, timed out).
- [x] `RefreshKeptTest` → green; **bites**: the 401 path's `putSecret` unguarded (red at `respond`), the resume's
  unguarded (red, `SignInFailure`).
- [x] `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :bridge:jvmTest --rerun` → both
  `LiveBridgeTest` cases green: the new one signs in with `remember` over `SoftwareDeviceKeys`, a fresh bridge resumes,
  a cancelled prompt stays on the unlock, the device key unlocks, Manage says DEVICE_KEY, sign-out; then the password
  trust, a fresh bridge unlocks with the password, Stop trusting, and the next start signs in.
- [x] `DeviceTrustLiveTest` ran: same variables, `./gradlew :core:jvmTest --tests '*DeviceTrustLiveTest*' --rerun` →
  green; with `VEYQ_ENV_FILE=/nonexistent` it is red (`FileNotFoundException`), so it ran live. The core's JVM tests
  take `VEYQ_ENV_FILE` now.
- [x] `linux/run.py build` → clippy, `cargo test`, build green; `linux/run.py bridge` → exports and smoke green (the
  container has no bus: `"staySignedIn":"NONE"` beside the sign-in); `linux/run.py test` → `PASSED 200`, then `no-bus`
  and `absent` `PASSED 1` each.
- [x] `windows/run.py build` → green (`Veyq.Bridge.Tests` here and there, `Veyq.Tests`, the app and `Veyq.Drive`).
- [x] `windows/run.py bridge` → the export gate and the smoke green; the real `WindowsDeviceKeys` answered
  `"staySignedIn":"PRESENCE"` beside the sign-in in session 0 (the VM has a PIN), no prompt raised.
  `windows/run.py test` → `PASSED 201` (200 + `RefreshKeptTest`).
- [x] `./gradlew :core:jvmTest --rerun :core:macosArm64Test --rerun :bridge:jvmTest --rerun
  :core:compileBridgedMainKotlinMetadata --warning-mode all` → `BUILD SUCCESSFUL`, no deprecation (354 and 190 test
  cases, none failed); `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:compileDebugKotlin` → `BUILD
  SUCCESSFUL`. No dependency added, so no verification-metadata write.
- **Left for Phases 4 and 5 (both done in Phase 4):** "Manage servers"' hide switches have no call yet (`Hidden.setTenantHidden` /
  `setSpaceHidden` and the shell's rebuild after them); the wire carries what they show. A live `run.py bridge` on
  Windows (`VEYQ_LIVE`) now enrols the password trust under DPAPI and leaves its wrap on the dev instance, since the
  smoke never stops trusting.

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

**Built (Phase 4):**
- **The screens** (`linux/src/sign_in.rs`, `manage.rs`, `main.rs`), as D5 says, libadwaita's way:
  - the sign-in's "Stay signed in" is an `AdwSwitchRow`, off, in a group whose line says what it uses
    (`sign_in::remember_words`, drawing the core's `StaySignedIn`: `LOGIN` the new `signin_remember_hint_login`, EN and
    DE; `PRESENCE` the apps' hint; `NONE` hides it); "Add server" titles the sign-in so and gives it Cancel;
  - `Gate.Unlock` is a page of its own: the account, the password row for DEVICE (none for DEVICE_KEY, whose button
    sends `""`), Unlock, "Use another account"; **amended in Phase 5:** a DEVICE_KEY record's line and button are chosen
    by `StaySignedIn` (`sign_in::unlock_words`; the bridge now sends it beside such an unlock), `LOGIN` saying the resume
    failed with "Try again" (owner 2026-11-11);
  - "Manage servers" is an `AdwPreferencesDialog` from the switcher: a group per server (its trust as the line), each
    tenant an `AdwExpanderRow` with its shown switch and its spaces' `AdwSwitchRow`s, "Sign out of this server" and
    "Stop trusting this device" as `AdwButtonRow`s, "Add server" last. It closes when the way in leaves the shell.
- **The hide switches on the wire:** `Manage.HideTenant(connection, tenantId, hidden)` and `Manage.HideSpace(scopeId,
  hidden)`, one line each onto `Hidden`; the shell rebuilds after a space and re-reads the switcher's tenants after a
  tenant (a counter combined into the shell's watch, since the shell's state does not change), and "Manage servers"
  follows. Windows' Phase 5 uses the same calls.
- **(a) The orphaned DEVICE_KEY wrap** (`Account.trustThisDevice`): a `putSecret` that throws deletes the wrap just
  added before it rethrows, so the password fallback leaves nothing on the server (`RememberEnrolTest`, `commonTest`).
- **(b) The smoke** ends a live run with `Session.ForgetDevice` for the connection the shell names.
- **`run.py run`** runs the app under `dbus-run-session` with gnome-keyring-daemon (`--cap-add=IPC_LOCK`), `~/.local/share`
  on the named volume `veyq-linux-home`: the device store, `keyrings/`, and the collection's throwaway password
  (`veyq-run/keyring`, drawn in the container at the first start, never printed). `--no-secret-service` runs without bus
  and volume. The debug devlogin gains `--fill` (fills the form; the pass turns the switch and presses Sign in) and
  `--unlock` (`VEYQ_DEV_UNLOCK_PASSWORD`, which a DEVICE record's unlock takes once, Android's and Apple's flag).

**Met (2026-11-11, commits `f099a3b1e`, `a5e09bdc6`, `8cf3152dd`, `1d8a5b9f3`):** the pass ran against the dev instance
in the container, driven from headless system Chrome (the browser pane, hidden, lagged Broadway's frames); its log first,
then the screenshots (28, not committed).
- [x] **Staying signed in:** `run --devlogin --fill`, the switch turned and Sign in pressed → `session SignIn, stay signed
  in: Login`, `session Signed`, Manage `Some(DeviceKey)`. Window closed; `run` (no devlogin) → `session Starting`,
  `session Signed`: resumed, no sign-in, no prompt. "Stop trusting" → Manage `None`; closed; `run --devlogin` →
  `session SignIn` first.
- [x] **The password trust:** that sign-in, the switch off → Manage `Some(Device)`; closed; `run` → `session Unlock`,
  the password row shown; "Use another account" → `session SignIn` (empty form). `run --devlogin --unlock` → `session
  Unlock`, `unlocking with the dev principal's password (debug build)`, `session Signed`: the core's unlock path, no
  password typed by the agent. Then Stop trusting, Manage `None`.
- [x] **The switch's three states:** `LOGIN` the plain label with the login line; `run --no-secret-service` → `stay
  signed in: None`, the switch hidden, the sign-in's trust refused (`DeviceAuthFailed: not kept: Cannot spawn a message
  bus…`), Manage `None`, and the next `--no-secret-service` start signs in. `PRESENCE` is Windows' (Phase 5). The absent
  library stays measured by Phase 2's probe (`absent`), not this pass.
- [x] **The hide switches:** Turnverein's switch off → the switcher lists one tenant; Personal's off → the switcher's
  spaces lose it; both on → both back.
- [x] **Add server:** the sign-in titled "Server hinzufügen" with "Abbrechen", which returns to `session Signed`.
- [x] **Language and theme:** sign-in, unlock and Manage in English light and German dark; the hidden switch's sign-in in
  English dark and German light.
- [x] `/usr/bin/python3 src/mobile/linux/run.py build` → clippy `-D warnings`, `cargo test` (14, `remember_words`' among
  them), build green; **bite**: `LOGIN` given the presence hint → that test red, restored.
- [x] `/usr/bin/python3 scripts/validate_mobile_words.py` → OK; `scripts/validate_mobile_platform_guidelines.py` → OK
  (it refused "Add server"'s `list-add-symbolic` outside `create_action.rs` first; the row has no icon now).
- [x] `./gradlew :core:jvmTest --rerun :core:macosArm64Test --rerun :bridge:jvmTest --rerun` → `BUILD SUCCESSFUL`, 355
  and 25 JVM cases, none failed. `DispatchTest` 13 (the hide switches and their refusals); **bites**: the tenant's
  `setTenantHidden` dropped, the space's `managed` refresh dropped → each red. `RememberEnrolTest` **bite**: the wrap's
  delete dropped → red ("the wrap the refused token was for stayed on the server").
- [x] Live: `VEYQ_LIVE=… VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :bridge:jvmTest --tests '*LiveBridgeTest*' --rerun` →
  green, `hid and showed again: space Personal, tenant Turnverein`; **bite**: the shell's re-read after a tenant
  (`hiddenTurned`) dropped → red, timed out at the tenant step.
- [x] `linux/run.py test` → `PASSED 201`, then `no-bus` and `absent` `PASSED 1` each; `windows/run.py test` → `PASSED 202`.
- [x] The smoke: live `linux/run.py bridge` → green, `ok Session.ForgetDevice stops trusting this device`; the container
  has no bus there, so Linux's sign-in trusted nothing to begin with (`DeviceAuthFailed: not kept`). Live
  `windows/run.py bridge` → green with the same line after a trust that DPAPI kept — the run that left a wrap before.
- The volume `veyq-linux-home` was removed after the pass; nothing of it trusts the dev instance (each enrolment was
  ended by Stop trusting).

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

**Built (Phase 5):**
- **The screens** (`windows/Veyq/`), as D5 says, Fluent's way; their words picked by `TrustWords` (no rule: each answer
  the core's):
  - the sign-in's "Stay signed in" is a **`CheckBox`** above the button, unchecked, its line under it — **deviation from
    D5's `ToggleSwitch`:** Fluent keeps a toggle switch for a setting that takes effect at once and a check box for a
    choice a submit button applies, as Windows' own sign-ins ("Remember me") draw it. `PRESENCE` labels it with the new
    `signin_remember_hello` ("Stay signed in with Windows Hello") and `signin_remember_hint_hello`, `LOGIN` the plain one
    with `signin_remember_hint_login`, `NONE` hides it; "Add server" titles the sign-in so and gives it Cancel;
  - `Gate.Unlock` is `UnlockView`, drawn as Windows' own sign-in screen draws a known account: the picture, the email and
    server, the line, the password for DEVICE, one accent button, "Use another account" as a link;
  - "Manage servers" is `ManageDialog`, a `ContentDialog` from the switcher's (now enabled) entry: per server its name and
    trust line, each tenant an `Expander` whose header carries its `ToggleSwitch`, its spaces' switches inside (the
    expanded ones kept across the redraw each switch brings), "Sign out of this server", "Stop trusting this device",
    then the hint and "Add server"; closed by `share_done` (Apple's ManageView's word). It closes when the way in leaves
    the shell.
- **The unlock without a presence check (owner 2026-11-11, "passt so"):** `unlock_login_hint` ("Your session couldn't be
  continued just now.") and `unlock_retry` ("Try again"), EN and DE, for a DEVICE_KEY record under `LOGIN`; the
  `PRESENCE` screen keeps its words, and both buttons send the same `Session.Unlock("")`. The bridge sends
  `staySignedIn` beside a DEVICE_KEY record's `Gate.Unlock` too (`Bridge.kt`, `wordedByStaySignedIn`), so Windows and
  Linux (`sign_in::unlock_words`, Phase 4 amended) choose by it. Chosen by `StaySignedIn`, not the record's kept path: a
  key sealed under DPAPI before Hello was set up then reads as `PRESENCE` (the button still opens it, unasked).
- **Debug:** `run.py run --devlogin [--fill|--unlock]` (Linux's flags: `VEYQ_DEV_FILL`, `VEYQ_DEV_UNLOCK_PASSWORD`);
  `DevLogin.Take` fills the form and signs in unless fill-only, `TakeUnlock` answers a DEVICE record's unlock once.
  `Veyq.Drive`'s tree prints a toggle's `on`/`off`.
- **The app's log** says `session <gate>, stay signed in: <answer>` and `manage servers: <method per server>`.

**Met (2026-11-11, commits `9642555d0`, `ec42da0c7`, `d46501ad0`, `80b20e45b`), the part needing no PIN:** driven on the VM
against the dev instance with `run.py run` / `run.py drive`, the log read before each tree and each tree before a shot
(13 shots under `src/mobile/build/windows/shots/trust-*.png`, not committed).
- [x] **The switch's label on the VM (Hello set up):** `session SignIn, stay signed in: Presence`; the tree shows
  `CheckBox "Mit Windows Hello angemeldet bleiben" #signin-remember off` with the Hello hint (de-DE light), `"Stay signed in
  with Windows Hello"` (en-US dark). `LOGIN` and `NONE` are unreachable on this VM; held by `TrustWordsTest`.
- [x] **The password trust:** `run --devlogin --fill`, Sign in pressed with the box off → `session Signed`, Manage `Device`.
  The next start → `session Unlock`, the password field, "Dieses Gerät ist vertraut: Dein Passwort genügt."; "Anderes Konto
  verwenden" → `session SignIn` with an empty form. `run --devlogin --unlock` (en-US dark) → `session Unlock`, `unlocking
  with the dev principal's password (debug build)`, `session Signed`.
- [x] **Manage servers and the hide switches:** Turnverein's switch off and Persönlich's off → the switcher lists one space
  and one tenant; both on (in the next run, en-US: "Personal") → both back. The tenant shown now and its own space are
  disabled (`switchable`/`canHide` false). The expanded tenant stayed expanded across each redraw.
- [x] **Add server:** "Server hinzufügen" / "Add a server" with Cancel, which returns to `session Signed`.
- [x] **Stop trusting:** → `manage servers: None`, the button gone; the next start → `session SignIn` first.
- [x] **A defect the pass found:** Manage servers opened a second time crashed the app (its content panel still the closed
  dialog's ScrollViewer's child); fixed in `80b20e45b` (a panel per open), then opened twice in one run.
- [x] `/usr/bin/python3 src/mobile/windows/run.py build` → green: `Veyq.Bridge.Tests` here and there, `Veyq.Tests` 12
  (`TrustWordsTest` 3 new: the switch, the unlock, Manage's line). **Bite:** the `LOGIN` unlock given the presence words →
  `ARememberedUnlockSaysWhatOpensIt` red, restored.
- [x] `/usr/bin/python3 src/mobile/linux/run.py build` → clippy, `cargo test` 15 (`a_remembered_unlock_says_what_opens_it`
  new), build green. **Bite:** the same → red, restored.
- [x] `./gradlew :bridge:jvmTest --rerun` → 26 cases, none failed, `DispatchTest` 14
  (`aRememberedAccountsUnlockCarriesWhatStayingSignedInUses` new). **Bite:** the unlock arm of `wordedByStaySignedIn`
  dropped → that test red, restored.
- [x] `/usr/bin/python3 src/mobile/windows/run.py test` → `PASSED 202`.
- [x] `validate_mobile_words.py` → OK; `validate_mobile_platform_guidelines.py` → OK.
- [ ] ~~**The Hello pass (O2)**~~ — **moved to Phase 7:** D7 (owner 2026-11-11) replaced the sign-in's switch before the
  pass ran, so it runs over the offer instead. The checkbox built here goes in Phase 7.

### Phase 6 — The core: levels and the offer (D7)

`DeviceKeys` per level (`presence`), the kept level, `StaySignedIn` as the offered set, the action "stay signed in now at
level X" with the "asked" mark, the enrolment moved out of the sign-in, `Session.SignIn` without `remember`; Windows' and
Linux's stores answer both levels (Windows: Hello and DPAPI; Linux: `LOGIN` alone); the bridge's calls (the offer as
`Gate`-side state, `Session.StaySignedIn(level)`, `Session.NotNow`, Manage's change of level).

**Done when:** the contract (D3) covers both levels over `SoftwareDeviceKeys`, Linux's store and Windows' DPAPI half; the
flows' tests cover the offer, each choice, "Not now" remembered, a level changed in Manage (the wrap replaced, the old key
gone), each bitten; `DispatchTest` and `LiveBridgeTest` (live: sign in, take each level, resume) green; Android and Apple
compile against the changed interface with their old behaviour (their offer is Phase 8).

**Built (Phase 6):**
- **`DeviceKeys` per level** (`session/DeviceTrust.kt`): `sealUnlockable`/`openUnlockable(…, level: StaySignedIn)` and
  `deleteUnlockKey(level)`, one key per level shared by every account remembered at it. **Deviations from the plan:**
  the level is the enum, not `presence: Boolean`; `deleteUnlockKey` takes the level, and `Account.releaseUnlockKeys`
  deletes each level's key once no DEVICE_KEY record uses it (two servers may sit at different levels, so one delete for
  both would end the other's trust). **The level is kept in the record** (`TrustedDevice.level`), not only in the store:
  with both keys present an open must know which; a record kept before D7 reads as `PRESENCE` (`ThisDevice.trusted()`
  fills it in, so a host always sees it). A level the store does not offer is refused (`DeviceAuthFailed(false)`), never
  sealed at the other.
- **`StaySignedIn`** is the level (`PRESENCE`, `LOGIN`; `NONE` gone); `DeviceKeys.staySignedIn` is the **set** offered:
  - Windows (`WindowsDeviceKeys`): Hello set up → both, else `LOGIN`. PRESENCE is Hello's Passport key, LOGIN DPAPI, each
    kept apart (`veyq.wincred.unlock.presence` / `.login`), each with its own 15 s window; no DPAPI fallback for a
    presence seal without Hello. The old single `unlock` + `unlock.path` keys are not migrated (only the dev VM had one;
    Phase 5 ended with Stop trusting).
  - Linux (`LinuxDeviceKeys`): Secret Service → `LOGIN`, else none; PRESENCE refused (the item `unlock` is the LOGIN key).
  - Android (`KeystoreDeviceKeys`): secure lock screen → both, else none. The LOGIN key is a second Keystore AES-256-GCM
    key (`veyq.unlock.login`) without user authentication; the presence key keeps its alias. **Why none without a lock:**
    LOGIN's guarantee is that the device's own sign-in authenticates; with no lock nothing does, and the Master Key would
    open for anyone holding the phone. The password trust stays (open question 1).
  - Apple (`AppleDeviceKeys`, `KeychainStore.swift`): passcode → both, else none, for the same reason. The LOGIN item
    (`veyq.unlock.login`) has no `.userPresence` but is `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly`
    (`KeychainBridge.store(…, passcode)`): removing the passcode removes it.
  - `SoftwareDeviceKeys`: both by default (`staySignedIn` settable), a key per level; `prompts`/`authenticates` count and
    play the PRESENCE level only; `hasUnlockKey(level)` for the flows' tests. `NoDeviceKeys`: none.
- **The flows** (`Account.kt`, `SessionModel.kt`):
  - `signIn(…)` has no `remember`: `Door.trust(account, password)` always enrols the password trust (DEVICE).
  - **The offer** is `Gate.Signed.offer` (`SessionModel.Offer(connection, levels)`): set after a sign-in **and after a
    password unlock** (a sign-in to the person; it reaches the accounts password-trusted before D7) where the store
    offers a level, the account is not staying signed in and was not declined here; never in the share extension. It
    stands over tenant switches, goes with its account's sign-out, and a later sign-in's offer (or none) replaces it.
  - `stay(level)` answers it, `stay(account, level)` is Manage's; both through `Door.stay` → `trustThisDevice(null,
    level)` (renamed from `stayingSignedIn`): a level not offered is `TrustChange.FAILED` without asking; the level held
    is `DONE`; `CANCELLED` keeps the offer; any other answer ends it. A level taken forgets an earlier "Not now".
  - `notNow()` marks the account declined in the device store (`ThisDevice.declined`, `veyq.stay-signed-in-declined`,
    not secret); only "Not now" marks, so a cancelled or failed enrolment, a sign-out of a remembered account or Stop
    trusting offer again at the next sign-in.
  - **Back to the password trust** (`trustWithPassword(account, password)`): ADR 0005 §9 keeps one convenience wrap per
    device, so DEVICE is re-created, not kept beside DEVICE_KEY — which needs the password. `VeyqAccount.isPassword`
    checks it first by a login (challenge, verify) whose new session family is revoked at once; a wrong one is
    `WRONG_PASSWORD` and changes nothing.
  - Every failure keeps the trust the account had and never signs out (`AccountDoor.changed`).
- **The bridge** (`Wire.kt`, `Bridge.kt`): `Session.SignIn` without `remember`; `Session.StaySignedIn(level)`,
  `Session.NotNow`, `Manage.StaySignedIn(connection, level)`, `Manage.PasswordTrust(connection, password)` (let through
  by `SecretFreeSchemaTest` as typed inward); answers `Done` or `Refused` with `NO_OFFER`, `TRUST_CANCELLED`,
  `TRUST_FAILED`, `WRONG_PASSWORD`. `ManagedShown` gains `level` and `levels`. **Deviation:** `Update.Session.staySignedIn`
  is gone — the offer rides the gate and a remembered unlock words itself by `record.level` (Phase 5's
  `wordedByStaySignedIn` removed).
- **The hosts:** Rust and C# regenerated; both sign-ins **drop their switch now** (it could do nothing without
  `remember`; the offer is Phase 7's), the unlock's words take `record.level`, the logs say `offer: [...]` beside
  `Signed` and `level: …` beside `Unlock`. `smoke.c` signs in without `remember`. Android's and Apple's `AppModel.signIn`
  sign in, then `stay(PRESENCE)` when their switch is on (their old behaviour; off, the offer stays unanswered, unseen).

**Met (2026-11-11, commits `bea91c830`, `7ca2b8c25`):**
- [x] **The contract (D3), both levels:** `DeviceKeysContract` runs each offered level's seal/open/AAD/delete, "each level
  is a key of its own" where both are offered, and "a level the store does not offer is refused and never falls back".
  Over `SoftwareDeviceKeys` (both) and a LOGIN-only fake (`LoginOnlySoftwareDeviceKeysContractTest`), on the JVM and
  `macosArm64`; over gnome-keyring: `/usr/bin/python3 src/mobile/linux/run.py test` → `PASSED 221`, then `no-bus` and
  `absent` `PASSED 1` each (the probe asserts the offered set: `{LOGIN}` present, empty absent); over Windows' DPAPI half
  (Hello not set up in the test's store, so PRESENCE refused before Hello): `/usr/bin/python3 src/mobile/windows/run.py
  test` → `PASSED 222`. **No Hello prompt was raised:** the mingw tests run over `NoHello`/`PlayedHello`, never the
  Passport provider; the probe was not run (Phase 1 stays its proof).
- [x] **The flows' tests** (`commonTest`): `SessionModelTest` (18; 7 new: the offer appears only where the store offers
  and the account was not asked, each answer, "Not now" remembered across a sign-out and sign-in until a level is taken
  in Manage, a cancelled prompt keeps the offer and a failed one ends it without signing out, a level not offered refused
  unasked, the offer over a switch and gone with its account, Manage's level change and way back);
  `StayLevelTest` (3, through `AccountDoor` over a played server: LOGIN → PRESENCE replaces the wrap, drops the login key
  and the old token, the next resume asks once; a cancelled presence prompt changes nothing; back to the password trust
  — a wrong password told apart, the check's session revoked, the DEVICE_KEY wrap and unlock key gone, the password alone
  opens the next start).
- [x] **Bites**, each red then restored: `releaseUnlockKeys` a no-op (two `StayLevelTest` red); `isPassword` taking a
  401 for true (`WRONG_PASSWORD` expected, `DONE`); the declined mark ignored (three `SessionModelTest` red); a cancel
  ending the offer; the resume at PRESENCE whatever the record's level (three red); the previous wrap kept (two red: one
  wrap per device); the check's session not revoked; the not-offered check dropped; a level taken keeping "Not now"; the
  fake's two levels sharing one key (`eachLevelIsAKeyOfItsOwn`); the fake sealing a level it does not offer (the
  LOGIN-only contract); Linux's PRESENCE refusal dropped (`LinuxDeviceKeysContractTest.aLevelTheStoreDoesNotOffer…` red
  in the container); Windows' "no presence key without Hello" dropped (the contract reached `NoHello`: red, as did
  `presenceIsHellos…`); Windows' LOGIN given Hello's guard (three red). Bridge: `Session.StaySignedIn`'s level
  hard-coded (three `DispatchTest` red), `TRUST_CANCELLED` answered as `TRUST_FAILED`, the
  `Manage.PasswordTrust.password` allowance dropped (`SecretFreeSchemaTest` red), Manage not refreshed after the way
  back (red, timed out).
- [x] `./gradlew :core:jvmTest --rerun :core:macosArm64Test --rerun :bridge:jvmTest --rerun
  :core:compileTestKotlinMingwX64 :core:compileTestKotlinLinuxX64 :core:compileTestKotlinLinuxArm64
  :core:compileBridgedMainKotlinMetadata --warning-mode all` → `BUILD SUCCESSFUL`: 373, 209 and 26 cases, none failed;
  `DispatchTest` 14 (the trust's rewritten: "Not now" and the unlock by password, the offer at PRESENCE with a cancel,
  the unlock's level, the offer following the store, Manage's level change / way back / Stop trusting).
- [x] Live: `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_ENV_FILE=<main>/.run/.env ./gradlew :core:jvmTest --tests
  '*DeviceTrustLiveTest*' --rerun :bridge:jvmTest --tests '*LiveBridgeTest*' --rerun` → green twice in a row (the
  second run proves nothing was left behind); with `VEYQ_ENV_FILE=/nonexistent` all three red (`FileNotFoundException`),
  so they ran live. `DeviceTrustLiveTest`: password trust → LOGIN (nothing asked) → resume → PRESENCE (the login wrap no
  longer served, its key gone) → resume asked once, a cancel refused → back to the password trust (a wrong password
  `false`) → LOGIN → sign-out. `LiveBridgeTest`: sign in (the offer), LOGIN, a fresh bridge resumes unasked; Manage
  changes it to PRESENCE; a fresh bridge resumes asked; a cancel stays on the unlock, the device key opens; Manage's way
  back (`WRONG_PASSWORD`, then done); a fresh bridge unlocks with the password, the offer follows, "Not now"; Stop
  trusting; the next start signs in; no unlock key left.
- [x] `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug :android:testDebugUnitTest` → `BUILD
  SUCCESSFUL` (the app has no unit tests: `NO-SOURCE`). `./gradlew :core:assembleVeyqCoreDebugXCFramework` then
  `xcodebuild -project src/mobile/apple/Veyq.xcodeproj -target Veyq -sdk iphonesimulator build CODE_SIGNING_ALLOWED=NO`
  → `BUILD SUCCEEDED`.
- [x] `linux/run.py build` → clippy, `cargo test` (14: the switch's test went with the switch), build green;
  `linux/run.py bridge` → 12 symbols, the smoke green. `windows/run.py build` → green (`Veyq.Tests` 11: the switch's
  test went); `windows/run.py bridge` → the export gate and the smoke green.
- [x] `validate_mobile_words.py` → OK; `validate_mobile_platform_guidelines.py` → OK. No dependency added, so no
  verification-metadata write.
- **Left for Phase 7:** the offer's screens and Manage's level change (with a password field for the way back) on both
  desktops; the words `signin_remember*` the switch used are unused there now. **For Phase 8:** Android's and Apple's
  offer in place of their switch.

### Phase 7 — The desktops' offer

The switch of Phases 4 and 5 replaced by the offer on Linux and Windows; Manage's level change.

**Done when** a pass on each has run: Linux in the container (`LOGIN` and "Not now"); Windows on the VM — **the Hello pass
(O2)**: the offer, "Sign in with Windows Hello" (the enrolment's PIN), quit and start (the resume's PIN, the shell), quit
and start (Cancel: the unlock stays), "Stay signed in" chosen in Manage instead (no prompt at the next start), "Not now",
Stop trusting; de/en, light/dark; builds and guards green.

### Phase 8 — Android and Apple

Their switch replaced by the offer; their stores' level without a check.

**Done when** both apps run the same pass on the emulator and the simulator (Android: a throwaway AVD with a screen lock,
`veyq_pixel`'s PIN being unknown; Apple: Face ID enrolled and answered by `notifyutil`, `src/mobile/CLAUDE.md`'s traps), each
level and "Not now", unchanged elsewhere; `./gradlew :core:jvmTest :core:macosArm64Test`, `:android:assembleDebug` and the
Apple build green.

### Phase 9 — The docs

- D6: the security model's rows, both levels per platform.
- ADR 0123 decision 8 changed (D7) and its Consequences.
- The use cases `uc.account.trust-device` and `uc.account.remember-this-device`.
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

- **Q4 (open, Phase 6): Android and Apple without a screen lock offer nothing,** not `LOGIN`. Built so because LOGIN's
  guarantee is the device's own sign-in; the web offers its checkbox on any browser, so the owner may prefer `LOGIN`
  there. One line per store to change.
- **Q5 (open, Phase 6): the offer also follows a password unlock,** so accounts password-trusted before D7 are asked
  once. Say if it should follow the full sign-in only.
- **Q6 (open, for Phase 7): Manage's way back to the password trust asks for the password** (ADR 0005 §9, Phase 6's
  retro); its check is a login, so it counts as one at the server (a new session family, revoked at once).
- **Q3 (settled, owner 2026-11-11): the enrolment after the sign-in, every level offered, all four apps in this CR** — D7.
  It supersedes Q1+Q2's rule below for the choice; their reading of each level (what a check is, the web's level) stands.
- **Q1 + Q2 (settled, owner 2026-11-11, "passt so"; the choice superseded by Q3): one rule for both desktops — "Stay
  signed in" uses the strongest the device has.**
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
- **Phase 2, execution: gnome-keyring in a container failed twice before it ran,** both times with the daemon falling
  back to a prompter: as root it aborts unless the container may lock memory, and `--unlock` makes no login collection
  without `~/.local/share/keyrings`. Each was one probe run; both are in `src/mobile/CLAUDE.md` § Traps.
- **Phase 3, execution: the bites' restore went through an interactive `cp`** (this Mac's alias): the first restore was
  refused, so the next bite ran over the previous one; caught by checking the file after the loop. `command cp` restores
  since. Phase 3 asked no new question of a machine; the new calls held at the first run, and each bite turned red.
- **Phase 3, design: "staying signed in" got its answer from the store, not a host**, and the value named what it means
  everywhere (`PRESENCE`/`LOGIN`/`NONE`) rather than Windows' word (`HELLO`): Android and Apple without a screen lock
  then answer `NONE` honestly, which is the case Phase 2 found Linux in.
- **Phase 4, execution: the hidden browser pane showed a switch unturned after the click that turned it,** and the next
  click turned it back; caught only because headless Chrome's first shot showed it on. Broadway's frames lag in a hidden
  pane, the input does not: the pass moved to headless system Chrome at once, each state read from the log first. Now
  a trap in `src/mobile/CLAUDE.md`.
- **Phase 4, design: the brief's smoke leftover was Windows', not Linux's.** Measured before the fix: `run.py bridge` on
  Linux has no session bus, so its trust failed before any wrap was added. The fix is the same for both and proved on both.
- **Phase 5, execution: Manage servers crashed the app on its second open,** a XAML element reused under a new parent
  (the first `ContentDialog` kept it). The first open had passed every check; only opening it again in one run found it.
  Next time a dialog's pass opens it twice.
- **Phase 6, design: "back to the password trust" needed the password,** which the plan's "keep or re-create DEVICE"
  left open. Keeping DEVICE beside DEVICE_KEY would break ADR 0005 §9 (one convenience wrap per device); re-creating it
  needs the password, and nothing but a login can check one, so the way back logs in and revokes that session. Cheap
  to have asked at the plan: any change of trust while signed in from a resume holds the Master Key but never the
  password.
- **Phase 6, design: the level had to live in the record,** not only the store: with two servers at different levels
  both keys exist at once, so an open must be told which. "Kept beside the key" (Windows' path) was a one-key idea.
- **Next time:** a verb that waits for a person waits for the process it started and fetches its outcome whatever
  ended the wait; its brief to the owner lists every prompt the run may raise, the uncertain ones marked so.
