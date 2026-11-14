---
id: cr-app-device-linking
title: Device linking on every app, both directions — "Add a new device" and "Sign in with a QR code" (ADR 0059)
status: in-progress
satisfies: []
adrs: ["0059", "0004", "0005", "0026", "0028", "0058", "0136", "0137"]
invariants:
  - "the QR never carries a server, and no app follows one out of a scan (ADR 0059 §4): each side talks only to the instance it is already signed in to or typed into its sign-in form; the payload parser is anchored at both ends and refuses the other direction's marker"
  - "each direction keeps its own binding step (ADR 0059 §5): Add a new device compares the five-digit number on both screens; Sign in with a QR code transcribes the six-digit code into the new device, one attempt (§6)"
  - "the relay sees only the handle (ADR 0059 §11): the trusted device seals only to an answer whose MAC verifies under the channel key, the new device opens only an envelope whose MAC verifies"
  - "a linked device enrols at most a DEVICE_KEY wrap, never a DEVICE one (ADR 0059 §10)"
  - "the confirmation is a slide that names the device and says signing it out will not take the key back (ADR 0059 §7)"
  - "no secret crosses the bridge: the QR's text, the channel key, the grant and the refresh token stay in the core; the desktops get the code as a picture and send frames in (cr-core-qr-decoder D2, D5)"
  - "the core's pairing crypto answers to contracts/crypto/vectors/v1.json, the same vectors @veyq/crypto answers to"
  - "the web's linking behaves as before; an app and the web link with each other in both directions and both roles"
---

## Goal

No app can link a device: a new app is signed in only with the email, password and Secret Key, and a signed-in app
cannot let another device in. This CR builds ADR 0059 on every app — Android, iOS, macOS, Linux, Windows — in **both
directions and both roles**, interoperable with the web:

| | Add a new device | Sign in with a QR code |
|---|---|---|
| **trusted** (signed in, unlocked) | Settings → Add a device → **shows** a `veyqpair:` code, compares the number, slides to confirm | Settings → Add a device → **scans** a `veyqsignin:` code, shows the six-digit code, slides to confirm |
| **new** (at the sign-in form) | **scans** the trusted device's code, shows the number | **shows** a `veyqsignin:` code, takes the six-digit code typed |

Whichever device has a camera scans (ADR 0059 §1); a device without one offers only its showing half and says the
other device scans (`cr-core-qr-decoder` D4).

**Depends on** `cr-core-qr-decoder` (all phases) and `cr-app-account-settings` (the Settings page whose "Add a device"
entry this CR fills). The sign-in form is shared with `cr-app-kit-sign-in` (written in parallel): that CR adds the kit
to the form, this one the two linking entries; both edit `SessionModel`'s `SignIn` gate, so whichever lands second
rebases onto the other's fields and both CRs' phases on that gate run one after the other, never side by side.

## Context & constraints

- **Read first:** ADR 0059 whole; ADR 0004 § Transport and § Session family; ADR 0005 §9, §17–22 (the wraps, why a
  linked device gets no `DEVICE` wrap); ADR 0028 Decision 7 (the trust-state marks the envelope carries); ADR 0026
  (why §6 refuses a server-side gate); `uc.account.onboard-device` (both directions, E1–E5); `uc.account.sessions`
  (where a linked device becomes visible); `src/mobile/CLAUDE.md` whole.
- **The web's reference** (`src/web/src/features/account/pairing/`): `pairing.ts` (the markers, `mintPairingSecret`'s
  re-mint of a secret starting with `/`, `parseMarked`, `checkNumber`, the grant's JSON and its reader, the
  six-digit draw), `pairingChannel.ts`, `pairingAnswerPolling.ts`, `grantEnvelopePolling.ts`, `onboardTail.ts`,
  `AddDevicePanel.vue`, `JoinDeviceCard.vue`, `SignInWithCodeCard.vue`, `GrantReceiverOutcome.vue`;
  `src/packages/crypto/src/v1.ts` (`pairingHandle`, `pairingChannelKey`, `pairingMac`), `seal.ts` (`pairing-grant`).
- **Where what runs:** as in `cr-core-qr-decoder` § Context. A live test links **the principal's own account to a
  fresh core session** (the trusted side is a core login of the principal; the new side a second core client with no
  session) and signs the linked family out at the end; it never touches the owner's browser sessions. The instance has
  `core.device-linking` on (the dev stack's default — the phase's first read checks it).

## Measured at refine (2026-11-12)

Commands from the repo root.

| # | What | Command |
|---|---|---|
| R1 | **No app has a pairing flow.** The core holds only the `pairing-grant` seal context and `FieldKind.PAIRING_CODE` (`^[0-9]{6}$`); no handle, channel key, MAC, payload parser or grant reader; the sign-in form takes server, email, password and Secret Key only. | `grep -rn -i 'pairing' src/mobile/core/src/commonMain`; `grep -n 'data class SignIn' -A8 src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/SessionModel.kt` |
| R2 | **The vectors pin the channel, the core does not read them:** `pairing_channel` (secret → handle and channel key; the `pairing-answer`, `pairing-sign-in-answer` and `pairing-envelope` MACs), `fingerprint` (the check number is its first five-digit group), `seal.asym["pairing-grant"]`. `Conformance.kt` reads `fingerprint` and not `pairing_channel`. **Nothing pins the QR payload's grammar or the grant's JSON** — each implementation has only its own tests. | `grep -n '"pairing_channel"\|"fingerprint"' contracts/crypto/vectors/v1.json`; `grep -n 'pairing_channel\|"fingerprint"' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Conformance.kt` |
| R3 | **The core's primitives cover it:** `hmacSha256`, `hkdfSha256` (`Primitives.kt`), `sealTo`/`openAs` (`V1.kt`), `fingerprintHash` (`Trust.kt`), the trust marks (`session/TrustMarks.kt`). | `grep -n 'fun hmacSha256\|fun hkdfSha256\|fun sealTo\|fun openAs\|fun fingerprintHash' -r src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto` |
| R4 | **`POST /api/token/adopt` answers only with a cookie** (`native-apps.md` § Account names it the blocker) — **but an app does not need it.** ADR 0004 § Transport: refresh "answers in kind — cookie in, cookie out; body in, body out", and adoption is "the same rotation refresh uses". The core already refreshes with a body token (`TokenApi.refreshSession(RefreshRequest(token))`), and `listSessionFamilies` marks the caller's family `current: true`, which is the family id adoption would have returned. | `sed -n 79,92p docs/decisions/0004-session-tokens.md`; `grep -n 'refreshSession(RefreshRequest' src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/session/Account.kt`; `grep -n 'current: true' contracts/api/auth.openapi.yaml` |
| R5 | **The pairing endpoints are on the auth spec and gated by `core.device-linking`** (422 `capability_disabled` when off): `openPairingChannel`, `answerPairingChannel`, `delegatePairingSession`, `sealPairingChannel`, `collectPairingChannel`. `scripts/validate_pairing_shapes.py` pins their schemas' property sets. | `grep -n 'operationId: .*Pairing' contracts/api/auth.openapi.yaml`; `grep -n CORE_DEVICE_LINKING src/web/src/features/platform/capabilities.ts` |
| R6 | **The Use pages describe the web:** `account/adding-another-device.mdx` and `getting-started/sign-in.mdx`, each with its `de/` twin. | `git grep -li qr -- site/src/content/docs/account site/src/content/docs/getting-started site/src/content/docs/de` |

## Decisions

### D1 — An app spends the handed-over token at refresh, not at adoption; no contract or backend change

The new device spends the grant's refresh token **once at `POST /api/token/refresh` with the token in the body** —
the rotation adoption itself runs — keeps the successor where an app keeps a login's refresh token (memory, or the
platform's key store at the trust level chosen), and reads its family id from `listSessionFamilies`' `current` entry
(R4). Adoption stays the browser's verb: it exists to move a token *out* of script into a cookie, which an app has no
use for. A native variant of adoption (an optional `sessionScope`, a body answer) was weighed and rejected: it would
add a contract change, a backend change, and a way for a web caller that omits a field to get a body token — the
exfiltration ADR 0004 keeps the browser out of. **ADR 0059 §2 and ADR 0004 § Transport gain this sentence in phase 7.**
The "stay signed in" question is the app's own offer after the grant (as after its login), not a cookie lifetime;
ADR 0059 §2's ordering (asked before the session exists) binds the browser's cookie and not the app's key store.

**First measurement of phase 3, stop and ask if it fails:** a delegated family's token spent through refresh rotates
and lists as `current` for the spender, and the granting family still refreshes afterwards.

### D2 — The vectors grow the two shapes nothing pins (contracts, then the web)

`contracts/crypto/vectors/v1.json` § `pairing_channel` gains:
- **`payloads`** — accepted and refused scanned texts with the marker and the secret each yields: both markers;
  surrounding whitespace trimmed; 31 and 33 bytes refused; padding variants; a trailing character, a query, a path,
  `veyqpair://host/…`, a `https://` text, the other direction's marker for each parser, and lower/upper-case
  variations of the marker (refused: the marker is exact). ADR 0059 §4 is the rule a reviewer looks for by name; these
  cases are its executable form for both implementations.
- **`grant`** — the envelope's plaintext JSON (`v: 1`, `email`, `masterKey` in standard base64, `refreshToken`,
  `label`, optional `marks`) with refusals (`v: 2`, a missing field, a mistyped one, marks not in the writer's shape).

The web's `pairing.test.ts` reads both from the vectors file (as `@veyq/crypto`'s tests read theirs); the core's
`Conformance` reads them in phase 2. The web's behaviour does not change; a case the web's current parser decides
differently from the ADR is a finding, fixed in the web in phase 1, not written into the vector as it is.

### D3 — The crypto and the parsers are the core's, held to the vectors

`crypto/Pairing.kt` in commonMain: `handle(secret)`, `channelKey(secret)`, `answerMac`/`signInAnswerMac`/`envelopeMac`
and their verifies (constant-time compare), `checkNumber(transportPublic)` (the fingerprint's first group), the grant's
writer and reader, `mintSecret()` with the web's re-mint of a secret whose base64 starts with `/`, `drawCode()` (six
digits, uniform, from the suite's random bytes), and `PairingPayload.parse(text, marker)`. The transport identity is the
suite's existing X-Wing + Ed25519 identity, serialized as `serializeIdentityPublic` serializes it (R2's
`transport_key`). `Conformance` grows `pairing_channel` (all of it, D2's additions included); `checked` rises
accordingly, and each new check gets a bite in `ConformanceTest` (a swapped HKDF label, a MAC over the other
direction's statement, a parser that accepts a trailing path).

### D4 — The flows are one core model, `DeviceLink`, with four roles

`session/DeviceLink.kt` (ADR 0136 decision 2's shape): `showForNewDevice()` (trusted, Add a new device),
`scanSignInCode()` (trusted, Sign in with a QR code), `scanFromTrusted(server)` and `showForSignIn(server)` (new, from
the sign-in form). Each role's states mirror the web's cards: the code shown (the QR as a matrix for the phones, a PNG
for the desktops — `cr-core-qr-decoder` D2), waiting, the number to compare / the code to show / the code to type,
the slide, sealing or collecting, done, and the refusals (no answer before the channel's expiry; a MAC that does not
verify — said as "this code was not answered by your device", never retried in place, ADR 0059 §6 and
uc.account.onboard-device E2; the capability off; the scan refused because it is the other direction's code, said
with the other entry's name).
- **The new device's tail is the app's sign-in tail:** spend the token (D1), record the device in the register with
  the grant's suggested label as the device writes it (§9), apply the marks, open the tenants, then the app's offer —
  **restricted to its `DEVICE_KEY` levels** (§10); where the platform has none (Linux without the Secret Service),
  the account holds the Master Key for this run only, and the offer says it will need the password or the Emergency
  Kit next time (the copy is the ADR's consequence).
- **The server is the one the form names:** the new device's two entries sit below the server field and use that
  server; nothing in a scan can change it. A new device on another server than the trusted one simply sees no answer;
  the waiting state's words say "both devices must use the same server" after the first poll window.
- **Polling** follows the web's intervals and stops on close; a channel is closed when its page is.
- **Capability:** both Settings' entry and the two sign-in entries are offered only while `listInstanceCapabilities`
  lists `core.device-linking` for that server.

### D5 — The slide

ADR 0059 §7 asks for a slide, not a tap. Neither Material 3 nor the HIG ships a slide-to-confirm control; each app
builds one from its platform's slider (Compose `Slider` that confirms only at its end and springs back otherwise;
SwiftUI `Slider` likewise; WinUI `Slider`; GTK `GtkScale`), keyboard-operable (arrow keys to the end, then Enter) and
announced by the screen reader with the sentence it confirms. The sentence names the device and the consequence, in
the web's words (`words/words.json` takes the web's English and German).

### D6 — Bridge

`Link.ShowForNewDevice(connection)`, `Link.ScanSignInCode(connection)`, `Link.ScanFromTrusted(server)`,
`Link.ShowForSignIn(server)` → `Answer.LinkOpened(link)`; `Link.Code(link, px)` → `Answer.Bytes(image/png)` (the
code as a picture); `Link.Confirm(link, label)` (the slide's end), `Link.EnterCode(link, code)`, `Link.Close(link)`;
`Watched.Link(link)` → `Update.Link(shown)` carrying the state, the number or six-digit code to show, the suggested
label and the refusal — never the secret, the channel key, the grant or a token. Scanning goes through
`cr-core-qr-decoder`'s `Scan.*` with the link's scan opened by the link (`Scan.Open` of kind `link`), so the scanned
text goes straight into `DeviceLink` inside the core.

## Phases

- [x] **1 contracts and the web** — D2's vectors; the web's `pairing.test.ts` reads them; a web parser finding fixed
  there. · Done when: `pnpm run check` in `src/web/` and `src/packages/crypto/` green; the trust-chain guards green;
  a bite — one refused payload moved to the accepted list — turns the web's test red.

  **Status: done (2026-11-13).**

  **Built:**
  - `contracts/crypto/vectors/v1.json` § `pairing_channel` gains `payloads` (32 cases, each with the secret's hex or
    null under each of the two parsers; the note states the grammar: trim space/tab/CR/LF only, the exact
    case-sensitive marker, then the canonical standard base64 of exactly 32 bytes, padded or not, never starting with
    `/`) and `grant` (7 accepted plaintexts with what they yield, 4 of them `written` — the writer's exact bytes —, and
    26 refused). Generated by an independent Python script (base64, json) and spliced in as text; the section's note
    now names the core's `Conformance` beside `@veyq/crypto` as its readers.
  - `pairing.ts`: `parseMarked` follows the pinned grammar; the grant's JSON is split out as `writeGrant` /
    `readGrant` (exported, `sealGrant`/`openGrant` call them). `pairing.test.ts` reads both vector sections.

  **Met:**
  - `pnpm run check` green in `src/web/` (549 files) and in `src/packages/crypto/`; the trust-chain guards green.
  - Bites (`/bin/cp -f` restore, then green), each red: a refused payload moved to accepted (the CR's bite); the
    written grant's label escaped as `ü`; and one per web fix below, each reverted alone.

  **Deviations — the web's parser and reader decided five cases differently from the ADR's reading, fixed in the
  web (D2):**
  - a secret whose base64 starts with `/` (`veyqpair://…`, URL-shaped) parsed — the minter never writes it, so the
    parser now refuses it (§4);
  - a non-canonical encoding (the last symbol's unused bits set) parsed to the same secret — now refused, one text per
    secret;
  - `trim()` took every Unicode space (a no-break space, a BOM) — now only space, tab, CR, LF, so both implementations
    trim the same set;
  - `epochs` as a JSON array read as an object of index keys — now refused, as `marks` as an array;
  - a `masterKey` of any length read — now exactly 32 bytes.
- [x] **2 the core's crypto** — D3. · Done when: `./gradlew :core:jvmTest :core:macosArm64Test` green, `Conformance`
  checks `pairing_channel` whole, each D3 bite red once.

  **Status: done (2026-11-13).**

  **Built:**
  - `crypto/Pairing.kt` (commonMain): `Pairing(primitives)` — `mintSecret` (re-drawn while its base64 starts with
    `/`), `handle`, `channelKey`, `answerMac`/`signInAnswerMac`/`envelopeMac` and their verifies (constant-time
    `macEquals`), `checkNumber` (`#fingerprint`'s first group), `drawCode` (six digits, bytes from 250 up drawn
    again), `mintTransportKey`, and the wire forms the web writes: `transportAnswer`/`signInAnswer`,
    `readTransportAnswer`/`readSignInAnswer`, `sealGrant`/`openGrant` (MAC first, then the seal), refusals by name
    (`PairingRefused(PairingRefusal)`). `PairingPayload.of`/`parse(text, PairingMarker)` (the secret stays bytes in the
    core); `PairingGrant` (`write`/`read`, `wipe`, a `toString` without key or token) with `PairingMarks` — the crypto
    package's own shape; phase 3 maps it onto `session/TrustMarks`.
  - `Contexts.kt`: the three pairing statements in `Purposes.STATEMENT`, as `seal.ts` holds them.
  - `Conformance.pairingChannel()`: `pairing_channel` whole — handle and key, each statement's bytes, MAC and verify,
    what each MAC does not answer for, the check number against `#fingerprint.digits`, the 32 payload cases under both
    markers, the grant's accepted (and `written`), refused cases.
  - `PairingTest` (commonTest, every target): round trips through answer and envelope, each refusal by name with its
    control, the forced draws, a minted secret under its own marker only, the grant's `toString`/`wipe`.
    `ConformanceTest`: an HKDF bite now also names the pairing handle; a keyless HMAC turns each pairing MAC red.

  **Met:**
  - `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64 :core:compileKotlinMingwX64`
    green (BouncyCastle on the JVM, libsodium on `macosArm64`, same vectors; libsodium prints 256 cases). One
    `:bridge:jvmTest` run went red once at load 16 in `DispatchTest`'s share-link test (no pairing code on its path)
    and was green on its re-run. Both conformance tests now require `checked > 200` (was 60 / 40): with
    `pairingChannel()` left out the count falls to 185 and the JVM test is red (bitten).
  - Bites (`/bin/cp -f` restore, then green), all 13 red by a failing test, never by a compile error: the D3 three — the
    handle's HKDF label swapped for the channel's, the add-a-device MAC over the sign-in statement, a parser that
    accepts a trailing path — plus a Unicode trim, a leading `/` accepted, a MAC compare that says yes, the check
    number from the second group, any master-key length, marks past 2^53 − 1, the writer's key order, the draw's
    rejection removed, the secret's re-draw removed, and the vector bite (a refused payload moved to accepted).
- [x] **3 the core's flows** — D4 with `commonTest` over a played relay (both directions and roles; a forged answer,
  a forged envelope, the other direction's code scanned, a wrong six-digit code sent once and the pairing abandoned,
  a timeout); D1's first measurement; `LiveDeviceLinkTest` (`VEYQ_LIVE`): both directions end to end between two core
  clients of the principal, the linked family listed with the label, rotating, revoked at the end, the granting family
  refreshing after (the control). · Done when: core checks and the live test green; a bite — the trusted side sealing
  without verifying the answer's MAC — makes the forged-answer case red.

  **Status: done (2026-11-13).**

  **Built:**
  - `session/DeviceLink.kt`: one ceremony in one of four `Role`s (`SHOW_FOR_NEW_DEVICE`, `SCAN_SIGN_IN_CODE`,
    `SCAN_FROM_TRUSTED`, `SHOW_FOR_SIGN_IN`), the web's cards' steps as `Step` (OPENING, SHOWING, SCANNING, CODE,
    WAITING, CONFIRMING, SEALING, FINISHING, DONE, ENDED), `Refusal` (OFF, GONE, REFUSED, FAILED) and `Hint`
    (OTHER_DIRECTION, SAME_SERVER); state as `StateFlow<Shown>` + `watch` (ADR 0136 decision 2). The code reaches a UI
    only as `matrix()`/`png(px)`; scanning roles own their `QrScan<Unit>`, whose parser takes this direction's marker,
    skips the other's with `OTHER_DIRECTION` (the scan goes on, nothing is answered) and anything else silently.
    `confirm(label)` (the slide's end: delegate, then seal; a blank name refused), `enterCode` (one atomic move out of
    SHOWING — the one send), `close`. The trusted side polls only until the answer lands; the new side only for the
    envelope. Every role asks `/api/setup` for `core.device-linking` first (OFF), a `422 capability_disabled` is OFF too.
    Polls every 2 s (the web's), a link waiting on the other device says SAME_SERVER after 30 s, ends GONE at the
    channel's 10 min; an answer to a channel the server does not know ends GONE + SAME_SERVER.
  - Seams `DeviceLink.Relay` (unauthenticated verbs + `capable`) and `DeviceLink.Granter` (the trusted account:
    e-mail, a Master Key copy zeroed after the seal, `TrustMarks.forLink`, delegate, seal); `HttpRelay` and
    `AccountGranter` over the generated `PairingApi`; `DeviceLink.showForNewDevice(account)`/`scanSignInCode(account)`.
  - The new device's tail: `SessionModel.scanFromTrusted(server)`/`showForSignIn(server)` (null outside the sign-in and
    in the share extension; the gate — a held kit with it — untouched until the grant is spent), `Door.relay`/`Door.linked`
    (defaults throw; `AccountDoor` implements them), `VeyqAccount.linked`: marks raised (`TrustMarks.raise`, each the
    maximum), the token spent once at `/api/token/refresh` in the body, the family id from `listSessionFamilies`'
    `current`, write moment 1 under the grant's label. No trust is enrolled: `Offer(linked = true)` offers the device's
    DEVICE_KEY levels, even none (the run-only case, D4 / Decided 3), and its "Not now" is not remembered as a decline.
  - `VeyqAccount.refresh()` split out of `respond` (one rotation, single-flight). `LiveDeviceLinkTest` takes its devices
    from `LiveRun` (merged from the live-trust-hygiene work, whose `DeviceRegisterStore.leave` this phase had added
    too — one kept).
  - Tests: `DeviceLinkTest` (12, over `PlayedServer`, the QR drawn by one link read through the real decoder by the
    other), `SessionModelTest` +3, `TrustMarksLinkTest`, `LiveDeviceLinkTest` (3).

  **Met:**
  - **D1's first measurement holds** (`LiveDeviceLinkTest.aHandedTokenSpentAtRefreshRotatesAndListsAsCurrent`, live): a
    delegated family's token, collected through a real channel and spent at `/api/token/refresh` with the token in the
    body, answers 200 with a new body token; exactly one family is `current` for its holder; the successor rotates; the
    spent token presented again is 401 (the clone case, which revokes that family); the granting family still refreshes.
  - `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest :core:compileKotlinLinuxX64 :core:compileKotlinMingwX64`
    green; live (`VEYQ_LIVE=http://veyq.localhost:8080`, `VEYQ_ENV_FILE`): `LiveDeviceLinkTest` 3/3, not skipped — both
    directions between a core login of the principal and a fresh `SessionModel` device: numbers equal, the linked family
    listed on the trusted device under the given name before the linked device's own Settings ran, `current` for the
    linked device, rotating, revoked from the trusted device (then unreadable), the granting family refreshing after;
    every device a case signed in with (both trusted logins, the linked device) is taken off the account by
    `LiveRun.removeAll`; no wrap is enrolled.
  - Bites (scratchpad scripts, `/bin/cp -f` restore, compared equal; each red by its named test's own failure): the
    CR's — `readTransportAnswer` without its MAC check → the forged-answer case; and the envelope opened without its
    MAC, one more collect after the answer, the other direction's code without its hint, a second code sent, no end at
    the channel's expiry, no SAME_SERVER hint, the capability unchecked, a close that leaves the polls running, a spent
    token said as FAILED, the handle in the state, a blank name confirmed, a link's offer as a password sign-in's, a
    link enrolling the password trust, a linked "Not now" remembered, a link opened over the shell, a handed-over mark
    lowering the device's; live: the family id not read from `current`, the device named by itself not by the grant.

  **Deviations:**
  - The client keeps its own channel deadline (10 min, the server's ttl) besides the server's 404: the poll's 404 is
    the web's only end, but a relay that stops answering would otherwise leave a link waiting for ever.
  - `Shown` carries enums, not `Msg`s: the words are phase 4's.
- [ ] **4 Android and Apple** — the Settings entry (show and scan), the sign-in form's two entries, the slide, the
  words. · Done when: both builds green; passes on the throwaway AVD, the simulator and the macOS build, **each against
  the web**, driven in headless system Chrome (machine hint: `channel: 'chrome'`, `?devlogin`; the web scans through
  Chrome's fake camera, `--use-fake-device-for-media-stream --use-file-for-fake-video-capture=<the app's code as
  .y4m>`): the app as trusted and as new device in each direction, four runs per app, the app scanning with its test
  camera or the macOS camera; each run's linked family signed out after.
  **And the code stays out of the system's snapshots** (inbox #886, `src/mobile/CLAUDE.md` § Secrets on screen): the
  shown pairing code (its QR and its digits) and the scanner's camera are a secret on screen, so Android's screen of
  them is `FLAG_SECURE` and Apple's is covered while the scene is not active (`.secretOnScreen()`); each bitten as the
  sweep was — the task snapshot (`/data/system_ce/0/snapshots`) and the SplashBoard snapshot pulled and looked at,
  without the protection the code visible in it, with it covered.

  **Status: built, partly met (2026-11-14).** Android met whole (four runs against the web, the snapshot bitten);
  macOS three of four runs; the iOS Simulator's runs, Apple's snapshot bite and phone↔phone are open (below, § Open).

  **Built:**
  - Words (`words.json`, EN + DE): 56 `link_*` / `signin_link_*` keys and `offer_not_now_linked_hint`, the web's
    `pairing.*` words where it has them (the slide's sentence and the consequence among them), new ones marked by a
    `note` (the camera hints for each side, the other direction's code said with the other entry's name, OFF, the
    sealing step, the keyboard line under the slide, the new device's refused envelope); the "same server?" hint as
    Decided 2 wrote it: "No answer yet? Both devices must use the same server."
  - **Android:** `DeviceLinkScreen` — one full-screen Material dialog for all four roles, `SecureFlagPolicy.SecureOn`,
    not dismissed by a tap outside (only its close and Back end it); it draws `DeviceLink.Shown` and hands back the camera
    (`QrCamera` over the link's own scan), the six digits (`enterCode`, number pad, Done sends) and the slide.
    `AppModel` holds the open link (`showForNewDevice`, `scanSignInCode`, `scanFromTrusted(server)`,
    `showForSignIn(server)`, `linkAgain` = a new ceremony of the same role, `closeLink`), logs steps to `veyq-link`
    (step, refusal, hint — never a code, number or name) and, when a new device's link is DONE, shows the account the
    grant opened. Settings: "Add a device" is a section with "Show a code" and, where the device has a camera, "Scan a
    code" (was a disabled row). Sign-in: "Scan a code from another device" (where a camera can be) and "Sign in with a QR
    code", both over the form's server. The offer after a link says the linked "Not now" hint.
  - **The slide (D5, ADR 0059 §7):** Material's `Slider` confirming only when a drag that began at the start (≤ 15 %)
    ends at the end (≥ 98 %); otherwise it springs back, and a new gesture or key stops the spring. A tap on the track's
    end jumps there in one move and does not confirm. Keyboard: the arrow keys move it in ten steps (the web's), Return
    at the end confirms; Home/End/Page keys are taken and move nothing. A screen reader hears the sentence and confirms
    through the custom action of that name. Disabled while the name is blank.
  - **Apple:** `DeviceLinkView` — a sheet with its own navigation bar, `.secretOnScreen()` on its whole content, the same
    steps; the code drawn from `AppleLink.png` (one `NSData`), `LinkSlide` over SwiftUI's `Slider` with the same rules
    (`onEditingChanged` at the end confirms, else springs back; arrows in ten steps via `onKeyPress`, Return at the end,
    Home/End/Page taken; VoiceOver's named action). `AppModel` as Android's; the sheet sits over the sign-in and over
    Settings (on a Mac also over the main window while the Settings window is closed). Settings' section and the
    sign-in's two entries as on Android, Title Case (`apple_en`). `AppleLink` (appleMain) opens the four roles for
    Swift (no default arguments, no suspending decoder) and hands the scan as `QrScan<Any>`. Debug only:
    `VEYQ_DEV_LINK` / `VEYQ_DEV_LINK_DIR` (`DevLogin`) open a role and answer in the person's place where no tap is
    granted.

  **Met:**
  - Builds (after merging `claude/windows-cr-f6c73c` at f29a5c351): `:core:jvmTest :bridge:jvmTest :android:assembleDebug`
    green, `:core:macosArm64Test` green on its re-run (`MoveSheetTest`'s undo window red once, nothing of this phase on
    its path); the macOS build (ad hoc) and the iOS-simulator build (`generic/platform=iOS Simulator`,
    `CODE_SIGNING_ALLOWED=NO`) `BUILD SUCCEEDED`; guards (`scripts/validate_*.py` but guard_gaps) green. Before the merge,
    at load 250–290: `DeviceLinkTest`'s race case (phase 3's) red once by the channel's virtual-time expiry firing while
    the test waited on `Dispatchers.Default`, green on its re-run; `:bridge:jvmTest`'s `DispatchTest` red by timeouts only.
  - **Android ↔ web, all four runs** (throwaway AVD `veyq_link4`, Pixel 8, API 36, virtual-scene camera, deleted after;
    the web headless system Chrome, `channel: 'chrome'`, one persistent profile, its camera a `.y4m` of the app's code;
    the app driven by `uiautomator` taps and `input swipe`, its code shot from the framebuffer):
    A1 the app shows → the web scans: numbers equal on both screens (79943, 99883, 39106 across tries), the web signed in
    linked, signed out after. A2 the web shows its sign-in code → the app scans it (`adb emu virtualscene-image wall`
    with the web's code, the scene camera turned over gRPC): six digits typed into the web, the app `CONFIRMING` →
    slid **by keyboard** → `DONE`, the web signed in, signed out after. A3 the app is the new device scanning the web's
    code: the web (itself linked by the app first — no password is typed anywhere) showed, the app's scene camera read
    it, `SCAN_FROM_TRUSTED: WAITING → FINISHING → DONE`, the linked offer shown. A4 the app is the new device showing:
    the web scanned it, the six digits typed into the app (number pad), `SHOW_FOR_SIGN_IN: DONE`, the offer with
    "This device got no password: …" (no screen lock on the AVD, so no levels).
  - **The slide on the device:** with a blank name a full drag did nothing; a tap at the track's end did nothing; a drag
    to the middle sprang back; a drag from the start to the end sealed. Keyboard: End then Return nothing; nine arrows
    then Return nothing; the tenth arrow then Return sealed (logcat `veyq-link`).
  - **Snapshot, bitten:** Android's task snapshot (`/data/system_ce/0/snapshots/<task>.jpg`, `adb root`, HOME pressed
    with "Show a code" open) is blank with `SecureOn`; the same build with `SecureOff` wrote the screen with the QR and
    "Waiting for your other device…" into it (pulled, looked at); restored with `/bin/cp -f`, compared equal.
  - **macOS ↔ web, three of four** (`VEYQ_DEV_LINK`, ad hoc build, no trust enrolled): the Mac shows → the web scans
    (numbers 17893 equal, sealed, the web signed in); the web shows → the Mac's test camera scans (`CODE → CONFIRMING →
    DONE`); the Mac new device scans the web's code (numbers 25259 equal, `SCAN_FROM_TRUSTED: DONE`, offer
    `[PRESENCE, LOGIN]`). The fourth (the Mac new device showing, the web scanning) did not finish: its first try's web
    camera opened while the feed was rewritten ("Camera unavailable"), the retry met the dev backend restarting.
  - Cleanup: every web session signed out by the web's own sign-out, every app session by Settings' "Sign out" /
    "Sign this device out" (Android) or the debug sign-out (Mac); the one password trust a sign-in enrolled on the AVD
    removed by "Stop trusting this device" and "Remove this saved key"; the AVD's Settings listed only its own session
    after. Left: the device-register entries of the AVD, the Mac and the web profile (one each — no screen removes one,
    inbox #894's area).

  **Deviations:**
  - The sign-in's two entries are offered without asking the typed server for `core.device-linking` first (D4 asked
    for that): the server is whatever is typed, so the link asks it as its first step and says OFF ("This server does
    not link devices.") — the Settings entry keeps the capability gate.
  - A screen reader confirms through a named custom action (Android `CustomAccessibilityAction`, Apple
    `accessibilityAction(named:)`) rather than by adjusting the slider to its end: an adjustment is one move, which the
    slide refuses for a pointer too; the action is a deliberate choice of its own name, the sentence.
  - Keyboard: Return at the end, as D5 says — the web's slide confirms on reaching the end; both refuse a jump key.
  - Apple's runs are driven by debug hooks (`VEYQ_DEV_LINK`, `VEYQ_DEV_LINK_DIR`: the app writes the code it shows and
    the digits on screen, the pass writes the typed digits and the name), so the Apple slide gesture itself is not
    proven by a pass — no simulator panel or Mac window click was granted.

  **Open (the owner's or the next session's):** the iOS Simulator's four runs and Apple's snapshot bite (`.secretOnScreen()`
  removed → the SplashBoard snapshot shows the code): CoreSimulator wedged under the load (simctl and `actool` hung for
  20+ minutes) and the Mac crashed twice from memory, so no simulator ran; the Mac's fourth run; phone↔phone (Android ↔
  the Mac by the same posters and test camera); the Apple slide by a person (drag, arrows + Return, VoiceOver's action).

  **Retrospective:**
  - **An edge swipe is the system's back.** The first slides "did not confirm": `input swipe` from x=70 started in
    Android's gesture inset and closed the dialog (its `onDismissRequest`). Swipes start ≥100 px in; and a tap outside
    no longer closes the ceremony (`dismissOnClickOutside = false`).
  - **A spring-back animation fights the next drag.** A second drag within the spring-back was pulled back to 0.17; the
    next gesture or key now cancels it.
  - **A tap is a jump.** M3's Slider jumps to a tapped value and ends the gesture there, so "ends at the end" alone let a
    tap on the track's end confirm; the slide now also asks where the gesture began.
  - **A dead dev backend reads as FAILED.** Two A3 seals ended FAILED while the shared backend had been killed (`gradlew
    --stop` elsewhere); the link's own refusal was right, the cause was outside. Check `/api/setup` before chasing one.
  - **Without `?devlogin` the web joins through the app.** The coordinator's Vite reads no `.run/.env`, so the web was
    signed in only by being linked from the app first — which also proves app → web before every web-trusted run.
- [x] **5 the bridge** — D6 with `WireTest` samples, `DispatchTest` (open each role, a code picture, confirm, a tenant
  switch closing it) and `SecretFreeSchemaTest` unchanged. · Done when: `./gradlew :bridge:jvmTest` green.

  **Status: done (2026-11-14).**

  **Built:**
  - Wire (`Wire.kt`): `Link.ShowForNewDevice(connection)`, `Link.ScanSignInCode(connection)` (the trusted device's; a
    tenant open, refused `NO_TENANT_OPEN`/`NO_SUCH_CONNECTION`), `Link.ScanFromTrusted(server)`, `Link.ShowForSignIn(server)`
    (the new device's, through `SessionModel.scanFromTrusted`/`showForSignIn`; refused `NO_SIGN_IN` outside the sign-in)
    → `Answer.LinkOpened(link)`; `Link.Code(link, px)` → `Answer.Bytes(image/png)` beside the JSON (`drawnCode`, now
    also over a matrix the core encoded, so the link's text never leaves it; `NO_BYTES` while none shows);
    `Link.Confirm(link, label)` and `Link.EnterCode(link, code)` → `Done` or `NOT_TAKEN` (the core's atomic moves: one
    confirmation, one code); `Link.Close(link)`; `Watched.Link` → `Update.Link(link, LinkShown?)`, `LinkShown` the core's
    `Shown` field for field under wire enums of their own names (`LinkRole`, `LinkStep`, `LinkRefusal`, `LinkHint`).
    A scanning role's camera: `Scan.Open(kind = LINK, link)` registers the link's own `QrScan` (found as its direction's
    scheme, from `PairingMarker`; `NOT_OFFERED` on a showing link); `Scan.Close` of it only drops the id — the link closes
    its scan.
  - Dispatch (`Bridge.kt`): `Composition.trustedLink` (the library's `DeviceLink.showForNewDevice`/`scanSignInCode(account,
    decode)`; a test plays a granter); trusted links close with the tenant (a switch, a sign-out), a new device's stay to
    `Link.Close`/`shutdown`. A new device's link at DONE makes the account it signed in the open tenant (`opened(signedTo)`,
    as a sign-in call's). `Link.Close` in FINISHING drops the id and the watch but leaves the core's link to finish: the
    core's close there ends the state ENDED while its tail still signs in, so the bridge would never open that tenant.
  - Core: `DeviceLink.scanSignInCode(account, decode)` (the bridge's decoder); `DeviceLink.png` scaled by the drawn grid's
    side (it divided by the code's size without the quiet zone: a large `px` overran `QrPng.MAX_SIDE` and threw).
    `PlayedPairing.kt` moved from `commonTest` to `testDevice`, so `:bridge`'s tests play the same relay.
  - Hosts: the only changes the generated types forced — the Rust `Call::ScanOpen` literal gains `link: None`, the C#
    initializer `link = null` (a generated record's nullable field is `required`).
  - Tests: `LinkDispatchTest` (7), `SecretFreeSchemaTest.aDeviceLinkCrossesAsItsStateAlone`, `WireTest` samples of every
    new call, answer, update and enum value, `LiveLinkBridgeTest` (2).

  **Met:**
  - `LinkDispatchTest` 7/7 over two bridges and a played relay: both directions end to end through the calls, the code
    read back from `Link.Code`'s PNG through the core's decoder and fed to the other bridge's link scan as a camera's plane;
    numbers equal; a blank and a second confirmation `NOT_TAKEN`; 32 racing confirmations → one `Done`, one delegation,
    one seal; a short and a second six-digit code `NOT_TAKEN`; the new bridge's gate Signed with `offer.linked`, its
    tenant open; a close in FINISHING still opens it; a tenant switch closes the trusted link (watch null, id refused, its
    relay closed); every refusal by name; nothing sent outward on any watch carries the code's secret, a handle or a token.
  - `SecretFreeSchemaTest`: no field of any root named like a pairing secret (handle, payload, grant, envelope, channel,
    transport, marker; the check refuses a played shape with five such fields), and the link's outward shapes pinned to
    their fields.
  - Live (`VEYQ_LIVE=http://veyq.localhost:8080`, `VEYQ_ENV_FILE`): `LiveLinkBridgeTest` 2/2, not skipped — two
    `Bridge.live` instances, both directions, the trusted bridge's Settings listing the linked family under the name
    given, every device (both trusted logins, both linked devices) taken off by `LiveRun.removeAll`. Without `VEYQ_LIVE`:
    2 skipped.
  - Bites (scratchpad script, one mutation each, `/bin/cp -f` restore compared equal; each red by its named test's own
    failure, never a compile error): a `payload` field on `LinkShown` → `aDeviceLinkCrossesAsItsStateAlone`; the new
    device's tenant not opened at DONE → the end-to-end case; `Link.Close` in FINISHING not held off → the close case; trusted
    links outliving the tenant → the switch case; a refused confirmation answered `Done` → the race case; a link scan's found
    scheme wrong → both end-to-end cases; a trusted link without a tenant → the refusals case.
  - After merging `claude/windows-cr-f6c73c` (at be14a6da8): `./gradlew :core:jvmTest :bridge:jvmTest` green (core 662,
    bridge's classes all green, the live ones skipped); `linux/run.py build` green (clippy, cargo tests, build);
    `windows/run.py build` green (`Veyq.Bridge.Tests` here and there, `Veyq.Tests`, the app and `Veyq.Drive` built); guards
    (`scripts/validate_*.py` but guard_gaps) green. The bites ran at load 160–220 (another agent's runaway): slow, none
    red by a timeout.

  **Retrospective:**
  - **A close and a sign-in are two lifetimes.** The core's rule (a close in FINISHING ends the screen, not the sign-in)
    left its state ENDED while the account signed in, so a host keyed to DONE would never open that account. The bridge
    keeps the two apart (the host's watch ends, the link runs on). The phones' `AppModel` keys on DONE too and cancels its
    watch on close — whether a close within the tail leaves their shell unopened is not measured (inbox-worthy for phase 6).
  - **Shared test fakes belong where both builds compile them.** The played relay sat in core's `commonTest`, which
    `:bridge` does not see; moved to `testDevice` rather than copied.
- [ ] **6 Linux and Windows** — the same screens as phase 4 on the desktops. · Done when: both `run.py build`/`test`
  green; each desktop's pass links with the web in both directions (the desktop showing; and scanning through its test
  camera); `NoCamera` offers only the showing halves on the Windows VM.
  **And the code stays out of capture** (§ Secrets on screen): Windows' showing screen and its scanner through
  `ScreenSecrets.Guard`, bitten as the sweep was — `Veyq.Drive`'s `shot` (BitBlt) shows the desktop behind the window,
  and the code with `VEYQ_DEV_CAPTURABLE=1`; Linux has no capture to keep it from (the section says why).
- [ ] **7 docs and harvest** — ADR 0059 §2 and ADR 0004 § Transport: D1's sentence; `uc.account.onboard-device`
  notes the apps (no coverage change); `native-apps.md` § Account: the adoption blocker removed, what is built added;
  `site/src/content/docs/account/adding-another-device.mdx` and `getting-started/sign-in.mdx` (with their `de/` twins)
  say the apps do both directions — the sign-in page is shared with `cr-app-kit-sign-in`, which owns its § kit, this
  CR its § QR code. · Done when: the trust-chain guards and the site's `pnpm run check` green; this CR deleted.

## Definition of done

Every app links a device with every other app and with the web, in both directions and both roles; the core's
pairing crypto, the payload grammar and the grant's JSON are held to the same vectors as the web's; no secret crosses
the bridge.

## Owner questions

1. **Two desktops, neither with a camera** cannot link to each other (neither can scan); they use the password and
   Secret Key, or the Emergency Kit. The web has the same gap. A typed fallback (the 32-byte secret as nine groups of
   Crockford base32, the Secret Key's form) would close it at the cost of a third way in; I would leave it out.
   Confirm.
2. **Copy** for the slide's sentence and the "no answer — same server?" hint: take the web's words where they exist
   (they do for the slide), and confirm the new hint's tone.
3. **Linux without the Secret Service** (D4): a linked device there keeps the Master Key only for the run. Acceptable,
   or should the Linux app refuse to be linked in that state?

**Decided** (the coordinator, 2026-11-12, under the owner's standing mandate to decide and report; the owner may overturn any):
- 1: no typed fallback; two cameraless desktops use the password and Secret Key or the Emergency Kit.
- 2: the web's words where they exist; the new hint as written.
- 3: acceptable — a linked Linux device without the Secret Service holds the Master Key for the run only, as a signed-in one does.

## Retrospective

Phases 1–2 (the implementer, 2026-11-13):
- **Writing the second implementation is what found the first one's loose edges.** All five web findings (phase 1's
  deviations) surfaced while choosing what Kotlin's `trim`, `Base64` and number parsing should do — none was visible
  from the web's own tests, which only ever fed it texts it had minted. Next time: a vector section for a parser is
  written with the second implementation's library behaviour in view (whitespace sets, base64 padding and pad bits,
  JSON number forms), not only the ADR's rule.
- **A bite that fails to compile reads as red.** The first bite run counted `BUILD FAILED` only; it was re-run to show
  each red is a failing test. Next time: a bite script checks for the failing test's name, not the exit code.
- The ADR leaves canonical base64 and the whitespace set open; this CR's vectors are now where that reading is fixed.

Phase 3 (the implementer, 2026-11-13):
- **A bite that stays green can mean a redundant guard, not a missing test.** "A second code sent" did not bite: the
  `sent` flag sat behind a step check that already refused the second call, and was not atomic against a racing one
  anyway. Both became one `compareAndSet` out of SHOWING, and the bite then went red.
- **A played relay must keep the order the real one forces.** The first forged-envelope test planted the envelope
  before the trusted device had read the answer, so the trusted device's own poll collected it and the case read GONE.
  The real server cannot seal before a delegation; the test now waits for CONFIRMING first.
- **A live test's leftovers are part of its design.** The first live runs left device-register entries (a fresh test
  device per login, a linked one per case); the test now takes its devices from `LiveRun`, which removes each after
  the case. Entries from the runs before that stay until the owner clears them.
- **An independent review (2026-11-13) found six things the phase's own tests had not; each is fixed with a commonTest
  case that bites** (one mutation, red, `/bin/cp -f` restore):
  1. *`confirm` was not single-flight* (check, then a move whose result was ignored, then a launch): a double tap
     delegated twice, the second `404` ended the ceremony mid-seal. Now one atomic move out of CONFIRMING
     (`advance`, as `enterCode`'s); 32 racing confirmations move it once. That race first awaited the racers on
     `Dispatchers.Default` from a suspended `runTest` body, which let the scheduler jump virtual time to the channel's
     end while they waited for a thread (red under load, `expected 1 but was 0`); since 2026-11-14 they run on threads
     of their own behind one gate while the test thread blocks, 20 links per run, and check-then-act is red 20/20 runs.
  2. *A failing seal (`sealGrant`, the Master Key's read) and a seal that did not open after a verified MAC escaped*
     the SupervisorJob as uncaught (an Android crash) and left the state in SEALING / FINISHING, where no timer ends
     it. Both now end FAILED; the grant is still wiped in `finally`.
  3. *The move to FINISHING was unchecked while `finish()` zeroes the keys in place:* a close at the collect let the
     grant be opened under the all-zero channel key and the all-zero transport seeds — both as good as public, so a
     relay could seal and MAC a grant this device then spent. Measured by the bite: with the move unchecked, the
     planted grant reaches the tail. Now the move is atomic and checked, and every use takes a copy of the keys before
     its move (a close after it zeroes the link's, never the copy).
  4. *A close in FINISHING could cut the tail after the token was spent* and before its successor was kept (an
     orphaned family): the tail now runs `NonCancellable` — a close there ends the screen, not the sign-in.
  5. The transport seeds are now wiped in `finish()`, the minted secret in a `finally` (it stayed when `open` failed).
  6. *OkHttp's silent retry of the one-shot redeem* (from memory): measured, not reproduced — a server that read the
     POST and dropped the connection saw it once, also over the generated call on a shared client; OkHttp re-sends
     only what cannot have reached the server (a failed connect, a stale pooled connection). The redeem now runs
     through a client of its own, closed after (no pooled connection), still the generated `TokenApi`;
     `OneShotRedeemTest` holds the one arrival (bite: a second presentation, red). Engines: OkHttp on Android and the
     JVM, Darwin on Apple, Curl on Linux, WinHttp on Windows. Note the server's one-generation grace (ADR 0004)
     already re-mints for a caller one generation behind, so a single repeat would rotate, not revoke.
  Lesson: a bite that stays green after a fix can mean a second, accidental guard — here the zeroed keys hid the
  unchecked move until the test planted the grant a relay could actually make under them.
