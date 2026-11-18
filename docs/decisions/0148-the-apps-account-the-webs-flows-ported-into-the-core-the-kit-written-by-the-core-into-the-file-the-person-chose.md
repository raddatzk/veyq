# ADR 0148 — The apps' account: the web's flows ported into the core, the kit written by the core into the file the person chose
- Status: Accepted
- Built: no
- Date: 2026-11-18
- Model: [native-apps](../crosscutting/native-apps.md) (the flows: [keys-grants-and-trust](../crosscutting/keys-grants-and-trust.md))

## Context

The apps hold what surrounds signing in — the address, this device, devices and sessions, the devices with a saved key,
add a device, storage and map services ([native-apps](../crosscutting/native-apps.md) § Built). The web also lets a
member rename any of their devices, edit their per-tenant profile (username, real name, picture, the account-global
"ask before adding me to groups"), see and compare safety numbers, change the password, change the address, replace the
keys with a new Emergency Kit, and erase the account. Each is a flow over keys the web wrote once
([ADR 0060](0060-key-rotation-after-compromise.md) and [0111](0111-an-invitee-grants-itself-a-rotator-re-keys-its-own-scopes-and-a-key-chain-anchors-trust.md)
for the rotation, [ADR 0003](0003-operator-blind-password-auth.md) for the auth key, [ADR 0074](0074-address-control-is-recorded-where-nothing-else-proves-it.md)
decision 8 for the address, [ADR 0101](0101-an-account-is-erased-on-request-and-only-what-the-law-keeps-stays.md) for the
erasure, [ADR 0084](0084-a-members-profile-picture-is-a-sealed-attribute-of-their-tenant-profile.md),
[0115](0115-a-scope-key-is-replaced-never-chained.md) and [0127](0127-the-web-client-encodes-webp-with-its-own-build-of-libwebp.md)
for the picture, [ADR 0028](0028-identity-authenticity-tofu-verification.md) decision 3 and
[0104](0104-the-identity-pin-store-is-one-sealed-entry-per-pin-under-a-sealed-head.md) for the safety numbers).

An app's logic is the Kotlin core's and each platform draws only its own UI
([ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)); every byte the core signs,
seals or derives answers to `contracts/crypto/vectors/v1.json`. When this was decided the core read neither
`secret_key_proof`, `auth_attestation`, `rotation_stage`, `verification` nor `fingerprint`'s digits and chain code; it
wrote no Emergency Kit, encoded no WebP and called none of the operations these flows use.

## Decision

1. **The device register's screen is the sessions section the apps have; it gains the web's rename of any device.**
   The web has no register screen beyond *Devices and sessions*, which the apps already draw from the register. Each row
   the register names gets *Rename*, the web's `renameDeviceOfFamily`: the family's device, last write wins, the copy
   saying it renames every session of that device. The core's `AccountSettings.renameFamily(familyId, label)` writes
   through `DeviceRegisterStore.rename`; a family the register cannot name offers no rename (the web's no-op). No screen
   removes a register entry, as on the web: the enabler's union merge and eviction stay the rule
   ([en.account.device-register](../features/account/enablers/en.account.device-register.md)).

2. **One's profile: one core model behind two places, as the web.** `ProfileModel` (core, `session/`) reads
   `getMyProfile` for the current tenant, opens the name with the tenant key (the opener `People.realName` uses), holds
   the username, the name, the picture and `requireGroupAddConsent`, and saves as the web: `updateMyProfile` with
   `sealedName` and its epoch, then `setMyAvatar` or `clearMyAvatar`; a `409 stale_epoch` re-opens the tenant key and
   seals again once, a `409 username_taken` is a refusal of that field. The tenant key copy is zeroed when the model
   closes; without a tenant grant the name and the picture are disabled with the web's hint. Settings gains *Your
   profile*; one's own person page gains *Edit profile*, which opens the same model (ADR 0084 decision 6). After a save
   the shell's people are read again, so every name and picture updates.
   **The picture:** the platform picks an image with its own picker and draws the existing cover cropper with a square
   frame (`CoverFrame` with an equal width and height, zoom 1–3); the host renders the crop to 256 × 256 RGBA as it
   renders a cover's tiers; the **core** encodes WebP, steps the quality down to the web's budget, pads to 16 384 bytes
   and seals (`member-avatar`). The encoder sits behind one seam, `WebpEncoder`: libwebp's encoder from the web's pin
   (`core/webp/build_libwebp.py`: `makefile.unix`'s `LIBWEBP_OBJS` with `LIBSHARPYUV_OBJS`, one `libwebp.a` per slice)
   on every native target — Apple, Linux, Windows (`media/LibwebpEncoder.kt`) —, the host's
   `Bitmap.compress(WEBP_LOSSY, q)` on Android, whose core target is the JVM (`AndroidWebpEncoder`). No vector pins an encode — two encoders
   never agree byte for byte; what is pinned is the seal (`field_seal.member-avatar`) and the padded length.

3. **Safety numbers: the core's port, the state the web shows, the compare by scan or by digits.** Core
   `trust/SafetyNumbers` ports `fingerprintDigits`, `chainCodeDigits`, the `veyqid:2` payload, its parse and
   `compareScan` (foreign, outdated, mismatch, match); the QR schemes gain `veyqid`. `PersonTrust` gives a person page
   the web's state — not verified, verified, verified by {names}, new (under 14 days), rotated, replaced, can't confirm —
   from the anchoring and `listVerifications` (opened under the tenant key, `member-verification`); its `verify` ports
   `verifyPerson`: the statement signed by this identity at its head, sealed under the tenant's current epoch,
   `putMyVerification`. One's own page shows the digits, the chain code and the QR; another member's the state and
   *Compare* — by each platform's existing scanner, accepting `veyqid:` only, or by the digits. A safety number is no
   secret and gets no secure window.

4. **The vectors first.** Before any flow uses them, the core's conformance reads `secret_key_proof` (all four
   statements), `auth_attestation`, `rotation_stage`, `verification`, and `fingerprint`'s `digits` and `chain_code`, on
   the JVM and on libsodium. `v1.json` gains, generated by the independent Python implementation as its neighbours are,
   an `auth_attestation.change_password` case (the new auth public key's raw bytes signed by the current auth key) and a
   `safety_code` section (the `veyqid:2` payload from a hash and a head, and the parse verdicts). The web's suite and its
   safety-code test read both, so neither implementation is the other's test. The kit's PDF is no vector; fixtures hold
   it (decision 5).

5. **The Emergency Kit: written by the core, saved where the person chose, never shown.** `KitWriter` (core,
   `account/`) ports `kitPayload`, held to `emergency_kit_token` in the writer's direction, and writes the web's one A4
   page: the address, the Secret Key in Courier, the three address fields as form fields (the first holding the
   server's origin), the QR of the payload drawn as filled rectangles, the token in the PDF's `Keywords`; the standard
   fonts only and no creation date, so the bytes are deterministic. A kit the core writes is read by the web's import by
   each of its paths and by the core's own, and a test re-writes it byte for byte. As on the web the Secret Key is
   **never on screen**: *Save your Emergency Kit* → the platform's save dialog → "I saved it", enabled only once the core
   reported the file written → only then the flow's danger step. The core writes straight into the dialog's target —
   Android's `CreateDocument`, macOS's `NSSavePanel`, iOS's `fileExporter` over an in-memory document, Linux's
   `gtk::FileDialog` (the portal's path in the Flatpak), Windows' `FileSavePicker` — and the bridge carries the path,
   never the bytes ([ADR 0137](0137-the-bridge-carries-shown-bytes-beside-the-json-ranges-through-a-reader-and-files-by-path.md)).
   It is the one plaintext secret the apps write, and only where asked; a failed or cancelled save leaves no file the app
   made. Each platform's no-copy check holds it ([`src/mobile/CLAUDE.md`](../../src/mobile/CLAUDE.md) § Nothing on disk).

6. **Change the password and change the address: the core's ports, the cold proof first.** `ColdCredential` ports
   `proveColdCredential` and `checkColdCredential` over the core's unlock; the Secret Key comes through the sign-in's
   existing control (typed, pasted, a kit file, a scan) and is kept no longer than the call. `PasswordChange` ports
   `changePassword` step for step; after it this device's saved key is gone on the server, so the core forgets its local
   wrap as *Stop trusting* does and the trust row offers *Trust with password*; the offline store survives (the same
   Master Key). `AddressChange` ports `emailChange`'s request; the confirm is the web's alone (owner 2026-11-18): the
   mail keeps its ordinary web link and the web's page confirms as today, and the app takes the new address from its next
   account read — the next sign-in where the confirm ended its session (a confirm revokes the account's other families) —
   and then offers a new kit with the new address. A web page's *Open in the app* (a deep link that carries no token and
   only opens the app) is block H's (inbox #435).

7. **Replace the keys: the core ports the whole rotation and finishes rotations at every unlock.** `KeyRotation` (core,
   `account/rotation/`) ports `rotateKeys` — the cold gate, the new identity, Master Key and Secret Key, the key-chain
   step, the staged self-grants in chunks of 256, the rooted invites and join endorsements, the re-sealed pins and
   register, the doubly signed proof, the bounded publish retries and an unknown outcome locking the vault —, its
   `afterCommit` (trust marks carried to the new slot, node pins re-sealed, each application's Master-Key material
   through an `AppKinds.masterKeyRotated` hook), the re-signed verifications, and `finishRotation` with
   `commitRotation`, run at every unlock and while unlocked, so a rotation begun on the web is finished by an app too.
   The order is the web's: the gate, the cold proof, decision 5's kit step, "I saved it", a danger confirmation, the
   publish, each scope's status. The Master Key changes, so the offline store's header no longer opens and the store is
   wiped and refilled ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md)
   decision 2, as written); the gate's facts say so.

8. **Erase the account: the core's port, then the device forgets the account.** `AccountErasure` ports the preview,
   the last-owner choices (delete along, or a member as the new owner), the password-only attestation and
   `eraseOwnAccount`; then the core does for the device what the web does for a browser: wipes this account's offline
   store, removes its saved key, its trust marks and its remembered sign-in, and leaves the account list. The Server
   Owner is told why not (`409 server_owner_account`, the web's words).

9. **What the apps cannot do yet is said, not half-done.** The core holds no moderation key. Where the rotation plan or
   the erasure preview names one, or the account holds a sealed grant of it, the app shows the web's facts and *Do this
   on the web* with the server's address, and sends nothing.

10. **Where it lives.** The Settings page gains, in the web's order, *Your profile*, *Password*, *Email address*,
    *Secure your account* and *Erase account*, each a section that opens its flow and each a section the search finds.
    A flow of more than one step is each platform's own multi-step surface after its guideline: Android a full-screen
    dialog with M3 steps and `SecureFlagPolicy.SecureOn` where a secret is typed, iOS and iPadOS a sheet with a
    navigation stack and `.secretOnScreen()`, macOS a sheet, Linux an `AdwDialog` with an `AdwNavigationView`, Windows a
    `ContentDialog` per step under `ScreenSecrets.Guard`; the danger step each platform's destructive confirmation. The
    words are the web's where the web has them.

11. **The bridge.** Each flow is a model with an id (`Call.ProfileOpen` → `Answer.ProfileOpened(id)`,
    `Watched.Profile(id)` → `Update.Profile(id, ProfileShown?)`; likewise the trust, the password, the address, the
    rotation and the erasure) and plain calls per action, `Settings.RenameFamily(familyId, label)` among them. Typed
    secrets are inbound only, each listed in `SecretFreeSchemaTest`'s `typedIn`; the kit's save is `Kit.Save(model,
    path)`, a path, never bytes; a picture is a path and a pose, rendered by the host on request. Closing the tenant and
    signing out drop every model.

12. **Every pass is a throwaway's.** No test or pass of these flows signs in as the seeded principal or member. A dev
    tool, `src/web/scripts/throwaway-account.mts`, creates a throwaway tenant with its owner (and, asked, a member) on the
    dev instance — the principal's credentials used only to create the tenant on the admin listener — and writes its
    credentials to a file outside the repository, mode 0600; `--erase` erases the throwaway's account and its
    sole-member tenant along. The tool, and every destructive live test, refuses a file naming the principal or the
    member, and a live test **fails** on one rather than skipping.

## Consequences

- **The server learns nothing new.** The apps send what the web sends for each operation: the same operation ids, the
  same sealed fields, the same proofs; no endpoint and no field is added, and the backend is unchanged.
- **The core grows the account's key-touching flows**, each a port of the web's and each held by the vectors before it
  signs anything. The Secret Key, the password and the Master Keys live in the core's memory only for the call and are
  zeroed after it; none crosses the bridge outward.
- **The apps write one plaintext secret,** the Emergency Kit, and only into the file the person chose.
- **A rotation wipes every device's offline store**, the rotating device's included, and refills it.
- **libwebp's encoder builds for every native slice** (measured 2026-11-18, `/usr/bin/python3 core/webp/build_libwebp.py
  build/libwebp` from `src/mobile/`, libwebp v1.6.0 at the web encoder's pin): `linux_arm64`, `linux_x64` and `mingw_x64`
  with Kotlin/Native's clang, `macos_arm64`, `ios_arm64` and `ios_simulator_arm64` with Xcode's, in 50 s; each
  `libwebp.a` 0.79–1.10 MB, each defining `WebPEncodeRGBA`. So no Apple, Linux or Windows host encoder stands behind the
  seam.
- **A moderator replaces their keys and erases their account on the web** until the apps hold the moderation key
  (inbox #965).

## Alternatives considered

- **A separate "Trusted devices" screen** — a second list of the same families, which the web does not have.
- **Apple's ImageIO as the picture's encoder** — it decodes WebP and writes none.
- **A PNG picture** — the contract's padded length and every reader expect WebP (ADR 0127).
- **The platform drawing the kit's PDF** (`PdfDocument`, `UIGraphicsPDFRenderer`, cairo, WinRT's print) — four layouts
  of one document and four places the Secret Key's text is handled.
- **A print action** — the web has none; the saved PDF prints from any viewer, and a print path is one more place (a
  spooler keeps files) the Secret Key passes.
- **The kit shown on screen** — a screen is photographed, recorded and looked over; a file the person chose is not.
- **Confirming an address change in the app by pasting the mailed link** — too cumbersome for a person (owner
  2026-11-18). **A custom-scheme link in the mail** — often not clickable in mail clients, and its token can be taken by
  another app that claims the scheme; universal and app links cannot be had for a self-hosted domain.
- **Re-wrapping the offline store's key under the new Master Key on the rotating device** — content kept from before
  the rotation would stay readable to whoever held the old Master Key and this disk, against ADR 0060's forward secrecy.
- **A rotation without the moderation grants** — the server refuses it, and rightly.
- **Passes as the seeded accounts** — a password changed, a key replaced or an account erased there breaks the shared
  test data every other pass and every developer relies on.
