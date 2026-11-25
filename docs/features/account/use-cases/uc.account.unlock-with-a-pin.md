# UC: Unlock an account on a TV with its PIN

- **ID:** uc.account.unlock-with-a-pin
- **Primary actor:** A member of a household using the veyq app on a shared TV (Apple TV, Android TV / Google TV).
- **Goal:** Keep one's account on a shared TV without anyone else at the remote opening it: a six-digit PIN per
  account, which no one can search offline — not a thief with the TV, not the operator alone.
- **Preconditions:** The TV was signed in through *Sign in with a QR code*
  ([uc.account.onboard-device](uc.account.onboard-device.md), [ADR 0059](../../../decisions/0059-device-linking.md)), so
  it never saw the password; the TV has a non-exportable hardware key (Android Keystore — StrongBox, else the TEE; the
  Apple TV's Secure Enclave). Operator-blind throughout ([ADR 0160](../../../decisions/0160-a-tv-unlocks-an-account-by-a-pin-the-device-hardware-binds-and-the-server-counts.md)).
- **Postconditions (success):** The account's Master Key is open in memory on the TV and only that account's;
  the server's failure count for the wrap is zero.

## Main flow — set the PIN, then unlock with it

1. Right after the QR sign-in the TV offers its one level, **PIN** (ADR 0160 decision 1): the person types six digits
   twice with the remote's number pad. Declining keeps no wrap — the next start needs a new pairing.
2. The TV computes the **hardware factor `F`** from its non-exportable key, draws a **server share `S`** (32 random
   bytes), derives `KEK = Argon2id(PIN, salt = HKDF(F ‖ S, "veyq/v1/pin-kek"))` and the **verifier**
   `V = HKDF(F, "veyq/v1/pin-verifier" ‖ PIN)` (vectors `pin_kek`, `pin_verifier`), wraps the Master Key under the KEK
   and stores a **DEVICE_PIN** wrap with `S` and `V` (`POST /api/account/wraps`). It forgets `S`, the KEK and the PIN.
3. The TV keeps, in the clear, the account's **label** on its picker (by default the account's profile name) beside
   its trust record — no avatar, no content.
4. **Unlock:** the TV starts at its account picker; the person picks their tile and types the PIN. The TV derives `V`
   and sends it (`POST /api/account/wraps/{id}/pin-unlock`); the server compares it in constant time and answers `S`,
   resetting the count. The TV derives the KEK and opens the wrap. Only that account opens.
5. **Lock:** *Lock*, the switcher, a return from the background, and 15 minutes without input while nothing plays
   drop the Master Key and return to the picker (ADR 0160 decision 6).

## Alternate / exception flows

- **Wrong PIN:** the server counts one failure and answers how many remain (`403 pin_incorrect`, `attemptsLeft`).
- **The tenth failure in a row** deletes the wrap with its share and verifier; the server answers 404 from then on, and
  the TV forgets the account. It comes back only by a new QR sign-in. The count is the server's per wrap, so neither a
  reinstall nor a second device resets it.
- **Wrap gone** (404 — another device removed it in the device list, or a password change or reset deleted every
  device-local wrap): the TV forgets the account on its picker.
- **No server reachable:** the TV does not unlock (ADR 0160 decision 5); it keeps no offline store.
- **Change the PIN:** asks the old PIN first, through the same count; a new wrap with a new `S` and `V` is recorded
  before the old one is deleted (ADR 0160 decision 7).
- **Forgotten PIN:** a new QR sign-in; the TV never knew the password.
- **No hardware key** (a simulator, a device without one): the level is not offered; nothing is kept.

## Quality notes

- **Operator-blind:** the server holds the wrap, `S` and `V`, never `F`; it can test no PIN and open no wrap. The
  residual — the operator together with someone who runs code on the TV searches the PINs offline — is ADR 0160
  decision 4's, stated as it is.
- **The count binds every client:** it is the server's, so a malicious client cannot skip it.

## User impact

Everyone in a household can keep their own account on the TV behind their own six-digit PIN, and ten wrong tries
remove the account from that TV until it is signed in again from a phone.

## Related

- Feature: [Account & auth](../_index.md)
- Concepts: [Account & Key Model](../enablers/en.account.key-model.md) (the fourth level, `DEVICE_PIN`)
- Decisions: [ADR 0160](../../../decisions/0160-a-tv-unlocks-an-account-by-a-pin-the-device-hardware-binds-and-the-server-counts.md),
  [ADR 0159](../../../decisions/0159-the-veyq-app-runs-on-apple-tv-and-android-tv-as-a-viewing-form-of-the-one-app.md),
  [ADR 0005](../../../decisions/0005-unlock-methods-and-device-trust.md), [ADR 0059](../../../decisions/0059-device-linking.md)
- Related use-cases: [onboard a device](uc.account.onboard-device.md) (the QR sign-in that precedes it),
  [unlock](uc.account.unlock.md), [remember this device](uc.account.remember-this-device.md) (the other levels)
