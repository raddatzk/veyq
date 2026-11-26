# ADR 0160 — A TV unlocks an account by a PIN the device's hardware binds and the server counts

- Status: Accepted (owner 2026-11-25: one PIN per account per TV, a constant 10, the idle lock fixed); amended 2026-11-26
  (owner: staying signed in on a TV without a PIN, a deliberate choice with a warning — § Staying signed in without a PIN)
- Built: yes — contract, backend, the core and both TV clients; the PIN never ran end to end: the Google TV
  emulator's Keystore is software and the tvOS Simulator has no Secure Enclave, so a real TV owes the pass (inbox #1360).
  The 2026-11-26 amendment is built in the core and both TV clients and held by `TvSessionModelTest`; its live pass on a
  simulator and on a real TV is still owed
- Date: 2026-11-25
- Model: [en.account.key-model](../features/account/enablers/en.account.key-model.md)

## Context

A TV is shared by a household and is signed in by [ADR 0059](0059-device-linking.md)'s *Sign in with a QR code*: it
never sees the password, so [ADR 0059](0059-device-linking.md) § 10 lets it keep at most a `DEVICE_KEY` wrap. The app
offers that wrap at two levels ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md),
"Right after it, an offer"): **presence** (the device asks for biometrics or its screen lock) and **login** (the
device's own sign-in authenticates). Neither exists on a TV, and the owner already ruled out a level that authenticates
nobody (owner 2026-11-11: a store with no screen lock offers neither). Today a TV would therefore keep no wrap: each
start would be a new pairing. And a TV with a wrap that opens without asking would open every account on it to
whoever holds the remote.

So a TV needs a level of its own: a short PIN per account. A PIN of 4–6 digits is worth nothing as a KEK by itself —
an offline search over 10⁶ values from a stolen device or a database dump takes seconds. It is worth something only if
something outside the attacker's reach limits the attempts. What the platforms give, verified 2026-11-25 (sources in
the CR's § Platform facts):

- **tvOS:** Apple TV HD and later have a Secure Enclave, and Secure Enclave keys (`kSecAttrTokenIDSecureEnclave`,
  CryptoKit `SecureEnclave`) are available on tvOS. **`LAContext` is not** (its platform list omits tvOS), so there is
  no `.userPresence`, no `.applicationPassword` and no system-counted app PIN. tvOS profiles switch from Control
  Center without a passcode.
- **Android TV:** the CDD requires a TEE-backed Keystore on TVs (9.11 [T-0-1]); StrongBox is only recommended.
  A key bound to user authentication can be generated only where a secure lock screen is set
  (`KeyGenParameterSpec.Builder`), which most TVs lack, and whose PIN is the household's, not a person's. Hardware
  attempt limits exist for the lock screen only (9.11 [C-0-2], 9.11.1); Keystore offers an app no counted PIN
  (remembered: no such API — none was found in `KeyGenParameterSpec`).

Neither platform gives an app a hardware-counted PIN. Both give a **non-exportable hardware key** that runs without a
lock screen. The attempt counter has to come from somewhere else, and the one party every unlock already talks to is
the server.

## Decision

1. **A fourth level, `PIN`, beside presence and login — offered where the device has a non-exportable hardware key
   and the level is the device's way to keep an account.** On a TV it is the only level offered: the choice is a PIN or
   no wrap at all (each start a new pairing). *Amended 2026-11-26:* beside the PIN, never before it, a TV now also offers
   staying signed in without one — see § Staying signed in without a PIN. It is the store's answer (`DeviceKeys.pinFactors`, null where the store
   has no hardware key), as the other levels are, never a screen's probe. In code it is no `StaySignedIn` entry but the
   method `TrustedDevice.Method.DEVICE_PIN`: its key is per account, where a `StaySignedIn` level is one unlock key
   shared by every account at it, and its open is the server's (decision 3), not the store's. Six digits, digits only (a remote's number pad; the same length as ADR 0059's
   transcribed code). One PIN per account on this device, never one per TV: the key material is per account, and a
   PIN shared by the household opens every account to every member of it — the separation it exists for.

2. **The wrap is a new method, `DEVICE_PIN`** ([ADR 0005](0005-unlock-methods-and-device-trust.md) § 20: a distinct
   construction earns a distinct method). Its KEK is derived from three parts, none enough alone:
   - **`F` — the hardware factor:** a fixed-input output of a key that never leaves the device's hardware: on Android a
     Keystore HMAC-SHA256 key without user authentication (StrongBox where the device has it, the TEE otherwise), over
     the label `veyq/v1/pin-bind`; on tvOS, where the Secure Enclave computes no HMAC, an ECDH agreement of a Secure
     Enclave P-256 key with a fixed public point, hashed through HKDF, the key `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
     (remembered that the class is accepted without a passcode — the core phase measures it on a real Apple TV).
   - **`S` — the server share:** 32 random bytes the device draws at enrolment, sends once and forgets. The server
     keeps it beside the wrap.
   - **the PIN.**

   `KEK = Argon2id(PIN, salt = HKDF(F ‖ S, "veyq/v1/pin-kek"))` with the `DEVICE` wrap's Argon2id parameters, and
   the PIN's **verifier** `V = HKDF(F, "veyq/v1/pin-verifier" ‖ PIN)`. Both derivations are pinned as vectors in
   `contracts/crypto/vectors/v1.json` and held by the core's conformance suite (the web takes no PIN level, decision 8,
   so `@veyq/crypto` implements neither; the vectors were computed by an independent implementation over `@noble`). The
   wrap's `kdfParams` carry the Argon2id parameters alone: the salt is derived on the device and never sent — with it,
   whoever holds the wrap would search the PIN offline.

3. **The server releases `S` only to a matching verifier, and counts.** An unlock sends `V`; the server compares it in
   constant time with the one stored at enrolment and answers `S` or a refusal with the attempts left. **The tenth
   failure in a row deletes the wrap and its share**: the account on that TV is gone and comes back only by a new
   pairing from a phone. A success resets the count. The count is the server's, per wrap, so neither a reinstall nor a
   second device resets it.

4. **What each party can do — the reason for three parts:**
   - **A thief with the TV** (no code execution) can only guess through the app, so through the server: ten tries.
   - **A thief who runs code on the TV** computes `F` and `V` for any PIN on the device, but still needs `S` per guess,
     so still the server's ten tries.
   - **The operator** holds the wrap, `S` and `V` and lacks `F`: it can test no PIN and open no wrap. Operator-blindness
     holds ([ADR 0005](0005-unlock-methods-and-device-trust.md) § 12); `V` reveals nothing without `F`.
   - **The operator together with a thief who runs code on the TV** has everything and searches 10⁶ PINs offline.
     That is the residual, stated as it is. It is narrower than the login level's, where the device alone opens.

5. **The unlock is online.** A TV with no server reachable does not unlock. A TV keeps no offline store
   ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
   is off on a TV, [ADR 0159](0159-the-veyq-app-runs-on-apple-tv-and-android-tv-as-a-viewing-form-of-the-one-app.md)), so
   there is nothing an offline unlock could open. The server sees when an account on that TV is unlocked — an exact
   server-observed time, the metadata the invariant already allows, and no more than a session refresh at each start
   already shows.

6. **An unlocked account locks again on the way back and when idle, because now there is something to ask.**
   [ADR 0005](0005-unlock-methods-and-device-trust.md) § 6 dropped an idle re-lock because a timer without a prompt binds
   nothing; at the PIN level the prompt exists. A TV's account locks (the Master Key dropped from memory, the app back
   at its account picker, [ADR 0159](0159-the-veyq-app-runs-on-apple-tv-and-android-tv-as-a-viewing-form-of-the-one-app.md)
   decision 4) when the person chooses *Lock*, when the app returns from the background, and after 15 minutes without
   input while nothing plays. Inbox #808 stays open for every other level.

7. **A changed PIN replaces the wrap,** as a level change does ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md):
   the new one recorded before the old one is deleted), with a new `S` and `V`; it asks the old PIN first, through the
   same count. A forgotten PIN is a new pairing: the TV never knew the password, so there is nothing else to fall back
   to (ADR 0059 § 10).

8. **Not on the web, and not on the other apps in this decision.** The web has no hardware key to bind
   ([ADR 0005](0005-unlock-methods-and-device-trust.md) § 5). A phone or computer without a screen lock could take the
   level too; whether it should is inbox #1352.

## Consequences

- **A contract and a backend change:** the method `DEVICE_PIN` joins `MasterKeyWrap.method` (and `WrapMethod`'s
  classification: device-local, does not survive a credential change); the wrap carries `S` and `V` at enrolment and an
  unlock operation answers `S` or the attempts left. The operator-blind probe classifies the new operation.
- **The count is a policy the server enforces for real** — unlike a client-side rule, a malicious client cannot skip
  it. Ten is a constant; a Server-Owner setting for it would be the one instance-level PIN rule the server enforces for real, open with the owner.
- **A reinstall or a new tvOS user loses `F`**, so the wrap cannot open: the account needs a new pairing. The orphaned
  wrap is the device's register entry to remove, as a lost phone's is ([ADR 0058](0058-revoke-never-takes-key-material.md)).
- **Rejected — a PIN-only KEK with a counter in the app:** a stolen device or a backup falls to an offline search.
- **Rejected — the hardware factor and the PIN, counted by the app:** anyone who runs code on the device searches at the
  hardware's speed; tvOS and Android give no counted PIN to make the count the hardware's.
- **Rejected — the server checks the PIN itself (a verifier without `F`):** the operator would hold the wrap, the share
  and a PIN check it can run offline, and would open the account — a breach of the invariant.
- **Rejected — an OPRF over the PIN (Signal-SVR style):** it hides the PIN from the server, which `F` already does
  here; more mechanism for no further guarantee.
- **Rejected — one PIN per TV, or per instance or tenant:** see decision 1. What an instance or tenant can set is a
  policy honest clients follow (the count is the exception, decision 3), and on a TV a PIN is already the only way to stay signed in, so a "TVs must use a PIN" policy would change nothing.
  (Since 2026-11-26 the PIN is no longer the only way, § Staying signed in without a PIN; such a policy would still be
  one honest clients follow, so the reasoning stands.)

## Staying signed in without a PIN (owner 2026-11-26)

The owner's decision of 2026-11-26 ("Ja, als bewusste Wahl mit Warnsatz umsetzen"): a TV offers a **third choice after
the QR sign-in — stay signed in on this TV without a PIN** — as a deliberate choice with a warning that says plainly what
it means. It narrows the owner's rule of 2026-11-11 (no level that authenticates nobody) for a TV, and only as the
person's explicit choice.

**Why.** A household TV is often in a room where everyone with the remote is someone the account holder would let in
anyway, and a PIN typed with a remote at every start is friction that pushes people to *Don't keep me signed in* — a new
phone pairing at every start — or away from the TV. The owner chose to let the person decide, told what it costs.

**What is built.**
- **No new level, no new method:** it is the `DEVICE_KEY` wrap at `StaySignedIn.LOGIN`, the desktops' "Stay signed in".
  A TV's store offers that level alone (`DeviceKeys.staySignedIn` = `{LOGIN}` on a TV: Android's `KeystoreDeviceKeys(tv =
  true)`, Apple's `AppleDeviceKeys(tv = true)`), its unlock key bound to nothing but this device: on Android a Keystore
  AES-256-GCM key without user authentication (non-exportable; the Keystore is not backed up and the app allows no
  backup), on tvOS a Keychain item `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` without a passcode binding
  (`TvKeychain` refuses any protected or passcode-bound item and keeps every item this device only, never in a backup).
- **The offer** (`SessionModel.Offer`): on a TV `pin` as before and `levels = [LOGIN]` where the store keeps it. Both TV
  clients draw the PIN pad first and focused, then *Stay signed in without a PIN*, then *Don't keep me signed in*. The
  no-PIN choice is never taken at once: it opens a confirmation carrying the warning (`tv_stay_no_pin_warning`) whose
  Cancel is the default — tvOS an alert (the HIG's way to confirm a choice with a consequence), Android TV a step of its
  own with Cancel focused (Leanback's guided step). Where the TV has no hardware factor for a PIN but keeps the login
  level, the offer says it cannot protect the account with a PIN (`tv_pin_unavailable`) and offers the no-PIN choice
  and the skip.
- **The picker** lists accounts kept without a PIN beside those behind one, unmarked (neither platform's guidance asks
  for a mark, and the person chose it); picking one opens it at once — a resume (`SessionModel.pick`), whatever was
  open closed first, one account at a time as before. A TV still resumes nothing at start: every start, *Lock*, a return
  from the background and the idle lock return to the picker (decision 6 applies unchanged; the lock binds nothing for
  such an account, it only closes it).
- **The TV's settings:** an account behind a PIN can drop it (*Stay signed in without a PIN*: the warning first, then the
  current PIN through the server's count — whoever finds the account open must not take its PIN away unasked;
  `VeyqAccount.keepWithoutPin`); an account kept without one gets one by *Set a PIN* where the TV keeps PINs
  (`trustWithPin`, which asks nothing old: it adds a check). The picker's name travels with the account through both.

**The warning, as the apps say it.** "Anyone with the remote can open your account on this TV." On Apple TV it goes on:
"— from any other profile too, since profiles switch without a passcode." tvOS profiles switch from Control Center
without a passcode (Context above), so a profile is no lock: with the `runs-as-current-user` entitlement (ADR 0159
decision 5, still the owner's signing step) the account is listed only in its own profile, but anyone can switch to that
profile; without it, every profile shares the app's data. Google TV profiles are unverified (ADR 0159 decision 5), so
the Android sentence claims nothing about them beyond "anyone with the remote".

**The residual, stated as it is.** Such an account authenticates **nobody**: whoever holds the remote — a guest, a
child, a burglar — opens it, reads everything the account reads (still only viewing: a TV authors nothing and takes
nothing off the device, ADR 0159 decisions 2–3) and can report content as the account. A thief who carries the TV away
opens it too, until the account holder removes the device from another device's list ([ADR 0058](0058-revoke-never-takes-key-material.md)):
that removes its wrap and ends its session, so the next pick finds the account gone (held by `TvSessionModelTest`'s
played refusal, not run against a server). The server count of decision 3 does not apply: there is nothing to count.
Operator-blindness is untouched — the operator holds the wrap, never the TV's key; the operator together with someone
who runs code on the TV opens it, as at any login level. What the PIN gave, this choice gives up, and the confirmation
says so before it is taken.

**Rejected — the no-PIN choice as the default, or offered first:** the PIN stays the recommended way; the owner's
words are "a deliberate choice". **Rejected — a new `StaySignedIn` entry or `TrustedDevice.Method`:** the LOGIN level's
mechanism is exactly this one (a key that opens without asking); a new entry would add a branch to every store and every
client's exhaustive drawing (Linux, Windows) for no different construction.

## Decision log (cr-tv-app, 2026-11-25)

- **The wire:** `DEVICE_PIN` joins `MasterKeyWrap.method` and `AddWrapRequest` with `pinShare` and `pinVerifier` (32
  bytes each, required for it, refused for every other method); `POST /api/account/wraps/{id}/pin-unlock` answers
  `200 {pinShare}`, `403 pin_incorrect {attemptsLeft}` (its own body, `PinUnlockRefused`, written by `httpPinIncorrect`)
  or `404 not_found` — never had, removed, or the tenth failure, alike on purpose. The row is locked for the count; a
  per-account `USER` rate bucket sits on top. `PinUnlockSliceTest` pins it.
- **It rides `core.remember-device.passwordless`:** a TV never sees the password, so enrolling is refused (422) and the
  wrap withheld from the key material while that switch is off, exactly as DEVICE_KEY.
- **The vectors** were computed by an independent implementation over `@noble` (it first re-derived
  `seal.sym.master-key` byte for byte); `pin_kek` pins the production Argon2id cost, so the Kotlin conformance suite checks
  it only at production cost, the verifier and the salts always.
- **A TV's unlock renews its kept session first** (the refresh token kept as a remembered account keeps it), then asks for
  the share; a refused refresh, a wrap gone or a missing factor forgets the account on that TV.
- **Android's factor:** a Keystore HMAC-SHA256 key without user authentication, StrongBox first, the TEE otherwise —
  offered only where a probe key's `KeyInfo` level is hardware (TEE, StrongBox or unknown-secure), and a key created
  later is checked again. Measured: the Google TV emulator's level is software, so it offers no PIN.
- **tvOS's factor:** a CryptoKit `SecureEnclave.P256.KeyAgreement.PrivateKey` per account
  (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` + `.privateKeyUsage`, its `dataRepresentation` a generic-password
  item); the bridge returns the raw ECDH x-coordinate with the fixed point `P` and the core runs the HKDF with the same
  label as Android. `P` is hash-to-curve by try-and-increment — x = SHA-256("veyq/v1/pin-bind/point" ‖ byte i), the first
  i (here 1) whose x³ − 3x + b is a square on P-256, the even root — so nobody knows its discrete log (with the generator,
  `F` would follow from the public key); the app recomputes and validates it at first use and offers no PIN if it does
  not match. Offered only where `SecureEnclave.isAvailable`.

The published pages: [Use veyq on a TV](../../site/src/content/docs/getting-started/use-veyq-on-a-tv.mdx); the residual in [Known limitations](../../site/src/content/docs/security/known-limitations.md#a-tvs-pin-the-operator-together-with-code-on-the-tv).
