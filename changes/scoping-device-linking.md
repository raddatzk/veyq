# Scoping — Device linking (authorize a new device from a trusted one)

> **Status: refine / scoping only. Not a CR, nothing built.** Pre-work that will become `cr-device-linking-*`
> once the decisions below are made and a "go" is given. Deliberately not named `cr-*.md` so the
> change-request guard ignores it. Spun off from the Email theme (whose scoping doc has since been retired to
> the inbox) — this is **not an email feature** (needs no mail). All `path:line` facts are **measured**; anything inferred is marked.
> **[DECISION]** marks a call the owner must make.

## What we want to achieve (one paragraph)

Let a user sign a **new** device in by authorizing it from an **already-trusted** device (Signal-style "link
a device"), so the new device can unlock **without** the Secret Key / Emergency Kit. The trusted (unlocked)
device wraps the Master Key to the new device's **out-of-band-verified** public key over a channel the blind
server only relays — the operator sees ciphertext only. This is the E2EE-native answer to "log in on another
device": unlike an email magic-link (rejected in Email V1), it solves **auth *and* unlock**, because the new
device actually receives the key.

## 0. Core framing — this feature is already anticipated

**ADR 0005 explicitly reserves it as a future use-case + ADR and sketches the whole design** — measured, quote
`docs/decisions/0005-unlock-methods-and-device-trust.md:216-220`:

> "**Cross-device onboarding (QR / push):** a new device is onboarded by an existing **unlocked** device over
> an **E2E channel the blind server only relays** — the QR/push carry the new device's *public* key (no
> secrets), the existing device encrypts the Master Key / a fresh wrap to it. Its own use-case + ADR; must
> carry the **E2E unlock-transfer**, **QR-login phishing** and **non-enumerability** (ADR 0003) constraints."

Also `0005:148-151` (§16): "**Passkey auth and QR cross-device onboarding are deferred to their own
use-cases/ADRs.**" So this scoping *fills in* an already-blessed seam; it does not invent a new direction.

**The load-bearing simplification (measured basis, inferred conclusion):** there is **one** Master Key and
**one** identity private key per account (`en.account.key-model.md:26-31`, stable per `userId`), and a device
unlocks by opening **one** `MasterKeyWrap`. Device wraps are per-device and **non-syncing** (ADR 0005 §4,
`0005:34-40`). So **"link a device" reduces to "give the new device a `MasterKeyWrap` it can open."** There is
no per-device identity to provision — only a wrap addressed to the new device's key.

## 1. Current state (measured)

**What already exists and is reused:**

- **The wrap envelope.** `MasterKeyWrap` (`contracts/api/account.openapi.yaml:312-345`): `method` enum
  `[PASSWORD, PASSKEY, RECOVERY, DEVICE, DEVICE_KEY]`; `DEVICE_KEY` = a device-held random key, `symWrap(K,
  masterKey)`, no KDF, `kdfParams` carries only the AEAD iv. This is the natural shape for the wrap the new
  device ends up storing.
- **The out-of-band anti-substitution primitive (ADR 0028).** A Signal-style **safety-number** digest of an
  identity public bundle is **implemented**: `fingerprint()` `web/src/shared/crypto/v1.ts:321-330`, surfaced
  via `identityPins.ts:188 safetyNumber()` + `:197 myIdentityPublicKeyB64()` ("render my safety-number + a QR
  for the other person to scan"). A **working in-browser QR scanner** exists: `KitScanner.vue` (getUserMedia +
  jsQR, frames decoded client-side, nothing leaves the device). The **pin store** is client-side, synced,
  sealed under the Master Key (server sees only ciphertext, `0028:31-36`).
- **The crypto verb.** `asymWrap` to a *foreign* public bundle already exists (`v1.ts:337` — "wrap a scope key
  to another member's published identity"). Wrapping the Master Key to a new device's public key is the same
  verb.
- **A server→worker pull channel.** ADR 0021's operator-blind job queue: interactive clients **poll** and
  self-select opaque scope-bound envelopes; poll loops already ship (`grantDeliveryPolling`,
  `scopeRotationPolling`, `mediaRevisionPolling`).

**What is absent (net-new):**

- **No cross-device provisioning.** `POST /api/account/wraps` (`account.openapi.yaml:65-91`) is
  **caller-self-scoped** — it stores a wrap the caller *already computed* for *its own* device (method
  restricted to `[DEVICE, DEVICE_KEY]`), resolved from *this* device's token. It transports nothing
  device-to-device. `DELETE …/wraps/{id}` only untrusts the caller's own wrap.
- **No per-device key.** ADR 0028's bundle is the **account-global** identity key — its QR proves *account*
  identity, **not** a per-device pairing key. A linking flow must mint an **ephemeral per-device transport
  keypair** and render *its* public key as the QR (reusing the fingerprint digest shape + KitScanner decode).
- **No push.** Confirmed: no webpush/VAPID/WebSocket/SSE anywhere; explicitly deferred (ADR 0021 §1 "push
  channel … is a later seam"; ADR 0033 "Rejected (deferred) — WebSocket/SSE push"). Live features short-poll.
- **No device registry/naming.** Session family = "one login / device" is **built** (`auth.openapi.yaml:251`,
  ADR 0004), but the **session management + naming layer** ([uc.account.sessions](../docs/features/account/use-cases/uc.account.sessions.md))
  is **refined-but-unbuilt** — `SessionFamily` carries only an opaque `id`, and the device names live in a
  Master-Key-sealed client register that does not exist yet. So "you're linking *Kevin's iPhone*" has no data
  behind it until #111's slices B/C land.

## 2. The design — reuse vs. new

| Concern | Reuse (exists) | New (must build) |
|---|---|---|
| Key model | One Master Key; new device just needs an openable wrap | — (no per-device identity to mint) |
| Anti-substitution anchor | `fingerprint()`/`safetyNumber()` + `KitScanner.vue` QR decode (ADR 0028) | **Ephemeral per-device transport keypair** + its QR (digest shape reused) |
| Sealing to the new device | `asymWrap` to a foreign bundle (`v1.ts:337`); `DEVICE_KEY` wrap envelope | The **E2E relay envelope** carried device→device |
| Contract | `POST /api/account/wraps` stores a self-computed wrap | A **relay/pairing endpoint** (new device pubkey in → sealed Master Key out) |
| Device listing | Session families (one login/device, built) | The **E2EE session-naming** layer (`uc.account.sessions`) — designed, unbuilt |
| Waking the other device | ADR 0021 pull/poll loops | True push (SSE/WebSocket) — deferred everywhere |

**Net-new surface = ephemeral transport keypair + a relay endpoint + the pairing-request/approval ceremony.**
Everything else is reuse.

## 3. Operator-blind & security forks (the hard part)

ADR 0005 named the three constraints; each is a design fork:

- **Anti-key-substitution — the QR *is* the anchor.** The server relays the new device's public key; a
  malicious operator could substitute its own device's key and receive the Master Key wrap. The **QR scan
  carries the new device's pubkey out-of-band** (camera → trusted device), bypassing the server relay, so
  substitution is defeated — exactly ADR 0028's pattern, reused. **The scan is not UX polish; it is the
  security boundary.** A no-scan fallback (type a code) must preserve the same out-of-band property.
- **QR-login phishing (the sharpest fork).** If the **trusted device scans and approves** whatever QR it sees,
  an attacker can display *their* device's QR and trick the user into linking the **attacker's** device →
  attacker's device receives the Master Key. Mitigation is a ceremony, not crypto: the approving device must
  show an explicit, unmissable "**you are granting THIS device full access to everything**" confirmation, ideally
  with a short verification code the *new* device also displays so the user matches them. **[DECISION A]** how
  strict — plain confirm vs. matched-code vs. count-down/anti-tap-through.
- **Non-enumerability (ADR 0003).** Initiating a pairing must not become an oracle ("does account X exist / is a
  device online"). Pairing is initiated **from inside an authenticated/unlocked session** (the trusted device),
  so this is mostly free — but the *new* device's "I want to be linked" request must not leak account existence
  before a trusted device approves.
- **E2E unlock-transfer.** The Master Key travels **only** wrapped to the new device's ephemeral public key; the
  server relays ciphertext, never plaintext. The new device unwraps on-device, then enrols its own `DEVICE_KEY`
  wrap via the existing `POST /api/account/wraps`. The relay row is deletable after pickup.

## 4. The two UX directions — different cost (Kevin's framing, made precise)

The device **with a camera scans**; the device **without a camera displays**.

- **Direction 1 — new device shows QR, trusted device scans** (e.g. *browser without camera* shows QR → *phone*
  scans). The trusted device captures the new device's ephemeral pubkey out-of-band, `asymWrap`s the Master Key
  to it, posts the sealed blob to a relay resource the new device's session polls. **Needs no push**, simplest
  choreography (one-way seal after the scan). **→ the natural first slice.**
- **Direction 2 — new device scans, trusted device shows QR** (e.g. *phone with camera* wants in → other devices
  must be **woken** to display a QR). Two costs: (a) **waking** a trusted device — no push exists, so model it as
  a **poll-for-pending-pairing** the trusted online device's existing poll loop picks up ("a device wants to
  link — review it"), reusing ADR 0021; true push stays deferred. (b) A **two-way** bootstrap: the trusted
  device's QR can't carry the Master Key (it doesn't yet know the new device's key), so its QR carries a pairing
  channel key; the new device scans it, sends its ephemeral pubkey over the now-authenticated channel, the
  trusted device seals back. More moving parts.

## 5. ADRs needed

1. **Device-linking / cross-device onboarding** — the pairing ceremony, the ephemeral transport keypair, the
   relay envelope, and how the three ADR-0005 constraints are met. This is the "its own ADR" ADR 0005 reserved.
2. **The relay/pairing endpoint contract** — likely folded into (1): a new device posts a pairing request +
   ephemeral pubkey; a trusted session fetches pending requests, approves, and posts the sealed Master Key; the
   new device polls for it. Non-enumerable, short-lived, single-use.
3. **No new ADR for the anchor** — reuse ADR 0028 (safety-number/QR) and ADR 0021 (pull channel); cite, don't
   restate.
4. **Device naming** (`uc.account.sessions`) is **upstream, not adjacent** — **[DECISION C]** settled it: inbox
   #111 runs first, so its ADR-free sessions slice and its Master-Key-sealed device register already exist when
   this ADR is written, and the ceremony can name the device it is granting.

## 6. Rough scope (slices; order contracts → backend → web)

| Slice | Content | Depends on |
|---|---|---|
| **1 — Ephemeral device-key + QR (enabler)** | New device mints an ephemeral transport keypair; render/scan its pubkey reusing `fingerprint()` + `KitScanner.vue` | ADR 1 |
| **2 — Direction 1, push-free** | Relay endpoint (pending pairing + sealed-blob pickup); trusted device scans → approves (ceremony) → `asymWrap`s Master Key → new device unwraps → enrols its own `DEVICE_KEY` wrap | Slice 1, ADR 1/2 |
| **3 — Direction 2, poll-woken** | Poll-for-pending-pairing on trusted online devices (ADR 0021 pattern) + the two-way QR bootstrap | Slice 2 |
| **(precedes) — Device naming + revoke** | `uc.account.sessions` list/revoke + the sealed device register, so linking shows/lists named devices **and a mistaken grant can be taken back** | inbox #111 — decided to run first, **[DECISION C]** |

**Deliberately deferred:**

- **True push (SSE/WebSocket)** — Direction 2 rides poll-for-pending-pairing instead; real push stays the later
  seam ADR 0021/0033 already defer.
- **Passkey / WebAuthn onboarding** — ADR 0005 §16 defers it separately; not this feature.

## 7. Cross-topic synergy — worth naming

Device-linking **is** recovery path #1 ("another enrolled device still holds the keys",
`security-model.md:281`). A robust linking flow partially delivers the Recovery topic's device path. Kept as
separate topics (owner's call), but the Recovery scoping should consume this rather than reinvent it.

## 8. Decisions the owner must make

- **[DECISION A] Anti-phishing ceremony strictness** — plain "grant this device access" confirm vs. a matched
  short-code both devices show vs. stronger (anti-tap-through). Drives the highest real-world risk (a tricked
  approval hands over everything).
- **[DECISION B] Which direction first** — recommend **Direction 1 (push-free)**; Direction 2 as a later slice
  on poll-for-pending-pairing.
- **[DECISION C] Couple device-naming or not — DECIDED 2026-08-11: the device & session manager (inbox #111)
  runs *before* this feature**, which inverts this doc's earlier "link first, naming after" recommendation. The
  reason is not UX polish, it is remediation: **[DECISION A]**'s risk is a *phished approval* handing a stranger
  the Master Key, and there is no remote revoke today to undo it — so linking-first would ship the attack
  without its cure. A reversible grant also lets the ceremony be lighter than it would otherwise have to be.
  Consequence for this doc: #111 delivers §6's "(adjacent) — Device naming" row, plus the stable client-side
  `deviceId` the ceremony needs to name the new device at all.
- **[DECISION D] Waking mechanism for Direction 2** — poll-for-pending-pairing (reuse ADR 0021, recommended) vs.
  wait for true push infra (bigger, deferred).

## Measured vs. inferred

- **Measured:** one Master Key / one identity key per account and per-device non-syncing wraps
  (`en.account.key-model.md:26-31`, ADR 0005 §4); the `MasterKeyWrap` enum + `DEVICE_KEY` shape
  (`account.openapi.yaml:312-345`); `POST/DELETE /api/account/wraps` being caller-self-scoped
  (`:65-91,268-277`); `fingerprint()`/`safetyNumber()`/`myIdentityPublicKeyB64()` + `KitScanner.vue` + the
  sealed pin store (ADR 0028, `v1.ts:321-330`, `identityPins.ts`); `asymWrap` to a foreign bundle
  (`v1.ts:337`); session family = one login/device built + `uc.account.sessions` naming unbuilt (ADR 0004,
  `auth.openapi.yaml:243-251`); no push anywhere + ADR 0021 pull queue with live poll loops; ADR 0005's
  reserved cross-device-onboarding sketch (`0005:148-151,216-220`).
- **Inferred:** that "link a device" reduces to "an openable wrap" (from the measured key model); that the
  wrap envelope is reusable but a relay endpoint is new (from the caller-self-scoped wraps API); that a
  poll-for-pending-pairing can stand in for push (from the ADR 0021 pull model); that device-naming is a UX
  dependency rather than a hard one.
