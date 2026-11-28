# ADR 0148 — The apps' account: the web's flows ported into the core, the kit written by the core into the file the person chose
- Status: Accepted
- Built: yes — on every app (Android, iOS, macOS, Linux, Windows), 2026-11-19; passed on Android, Linux, Windows and
  macOS as throwaways (§ Consequences, the passes); what the macOS pass left is #976, the Windows pass's #983; the
  owner's passes are named in § Consequences
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
([ADR 0161](0161-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md)); every byte the core signs,
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
   wrap as *Stop trusting* does, the Settings page open reads anew, and its trust row offers each level and the password
   trust (core's `trustChanges`, inbox #1009); the offline store survives (the same
   Master Key). `AddressChange` ports `emailChange`'s request; the confirm is the web's alone (owner 2026-11-18): the
   mail keeps its ordinary web link and the web's page confirms as today, and the app takes the new address from its next
   account read — the next sign-in where the confirm ended its session (a confirm revokes the account's other families) —
   and then offers a new kit with the address the server holds — never before the confirm, when the account's address is
   still the old one (the web's order, its confirm page; inbox #1010). Which sign-in took it is told by the record this
   device keeps, or by the request this device remembers (`veyq.address-change.<account>`, forgotten with an erased
   account). A web page's *Open in the app* (a deep link that carries no token and
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

9. **What the apps cannot do yet is said, not half-done.** *(Superseded for the moderation key by § The moderation key,
   2026-11-28: a holder secures and erases the account in the app as on the web.)* Was: the core holds no moderation key;
   where the rotation plan or the erasure preview names one, or the account holds a sealed grant of it, the app shows the
   web's facts and *Do this on the web* with the server's address, and sends nothing.

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
- **Where it was built otherwise than decided** (2026-11-19): on Windows each flow is a page in the settings frame
  (breadcrumb Settings › the flow, Back), Fluent's sub-page, not a `ContentDialog` per step — so the danger step can be
  a dialog of its own (`Dialogs.Confirm`, accent-free, Cancel the default) — decision 10. The Secret Key of the
  password change, the address change and the rotation's gate was typed, or pasted as a bare key, only; the sign-in's control — a pasted kit
  token, the kit file and the scan — came to them with [ADR 0151](0151-the-apps-account-rest-the-sign-ins-secret-key-control-in-every-flow-that-asks-for-the-key-and-the-breached-password-check-straight-from-the-device.md) decisions 1–4 (inbox #970, built on every app 2026-11-19) — decision 6. On the desktops the bridge reopens the tenant after a rotation;
  the done rotation outlives that reopen, so its last step (each scope's state, the *Links to renew*) stays drawn in
  place — decision 7. The new password was held to the length
  policy only; the web's breached-password check came to the apps with [ADR 0151](0151-the-apps-account-rest-the-sign-ins-secret-key-control-in-every-flow-that-asks-for-the-key-and-the-breached-password-check-straight-from-the-device.md) decisions 5–7
  (inbox #969, built on every app 2026-11-19). An iOS kit is written through
  `fileExporter` with an in-memory document made only when the exporter asks; whether UIKit stages it in the
  container first is unmeasured (inbox #975).
- **The sign-in's two account entries came later** (2026-11-23, [ADR 0154](0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md)
  § As built: the sign-in's entries). The locked sign-in (uc.account.sessions E3) is the login alone, every device signed
  out, the way back keeping nothing; *Forgot your password?* asks for the recovery link as the web does. **The finish is the app's too** (owner 2026-11-26:
  every app does what the web does, replacing 2026-11-23's "the web finishes it"): the mailed link
  (`<tenant>/recover#<token>`) reaches the app through *Open a link* or shared text only — never `veyq://`, which carries
  no token (ADR 0150 § O1, § O5 rule 2) — and `session/RecoveryFinish` ports the web's `RecoverView` over `recovery.ts`:
  the material released (any failure the web's `linkSpent`), the RECOVERY wrap opened by the Secret Key alone through the
  flows' key control (`KeyIntake`, bound to the released address), a new password with the breached-password check, a
  fresh PASSWORD wrap, auth key and `secret_key_proof.recovery` sent once, every key zeroed at done and close; done hands
  the sign-in its server and address (`SessionModel.recovered`). The device's own trusted record of the account, where it
  has one, is left to the paths that already end a record whose wraps and sessions the server dropped (a refused
  refresh, a wrap no longer served), as on the account's other devices. Held by `RecoveryFinishTest` (the complete's
  proof and new wrap checked as the server checks them), `AccountFlowsDispatchTest`'s recovery case and, live as a
  throwaway, `LiveRecoveryTest` (the link read from Mailpit, the new password signing in to the same spaces).
- **The save dialogs' recent-files records are accepted** (2026-11-19, inbox #981). After a kit saved through GTK's
  dialog, `~/.local/share/recently-used.xbel` named the kit's path (measured in the Linux pass; in the Flatpak the
  portal keeps the same record on the host), and Windows' save picker leaves a `.lnk` under `Recent` (measured in the Windows pass). Such a record
  names the path the person chose, never a byte of the kit, and it is the place the person picked for a file they keep
  on purpose; removing it would reach only the app's own GTK manager, not a portal's or the shell's. So the no-copy
  rule is about content, and the Use page on the kit tells the person that the file is where they saved it, to be kept
  or deleted there like any sensitive file, and that the system may list it among recent files
  ([Your Emergency Kit](../../site/src/content/docs/getting-started/emergency-kit.mdx)).
- **What the passes proved** (each as a throwaway with a member, made and erased by the pass, both pending erasures
  listed). *Android* (API 36 emulator): a session renamed and read back; a name and a square-cropped picture saved and
  read back after an unlock; one's own safety number shown and the member verified by digits (*Verified by you.*); the
  address change to the mail step; a kit saved into Downloads through `CreateDocument`; the password changed, the old one
  refused, the new one signing in and the fingerprint re-trust; the rotation with a new password, both scopes *New
  lock*, the old Secret Key refused and the rotation's kit read by the sign-in; the erasure refused without the last
  owner's choice, then done, the device's offline store and record gone. No file of the app held a canary of the Secret
  Key after each kit save (bitten by grepping Downloads, which held the kit), and the Recents snapshots of every secret
  step were blank. *Linux* (Broadway): the same, over the bridge — this device renamed on a scrolled page that stayed
  put, a picture cropped and saved, the member's number equal on both apps and verified by digits, the password changed
  and changed back, a kit for the address and the rotation's kit saved through GTK's dialog, the rotation without a new
  password, the erasure with *Delete the space*; no file under the data, cache, config, temp or runtime directories
  held the Secret Key, `veyqkit:` or a PDF. *The core's live tests* as throwaways: verification both ways, a name and a
  picture read by the other member, the password changed and the old one refused, the rotation (both scopes secured,
  the old Secret Key refused, the new kit signing in to the same spaces), the erasure. A kit the core writes is read by
  the web's import by each of its three paths (`kit-app.pdf`). *Windows* (the VM, UI Automation, the unpackaged debug
  build, 2026-11-19, on the fixed tenant `tw-win.localhost` the throwaway tool's `--tenant` names): a 600 × 400 picture
  framed, saved and read back in the shell and on the person page with the name; one's own safety number and the
  member's verified by digits (*Verified by you.*), each side's number equal on the other's app, before and after the
  rotation; this device renamed; the password changed and changed back, the old one refused; the address change's
  request and its kit through `FileSavePicker` behind *I saved it*, that kit signing in; the rotation without a new
  password, both scopes named *New lock*, the old kit refused and the rotation's kit signing in; the member's erasure in
  the app (its offline store gone), the owner's erasure preview listing the sole tenant (the owner erased by the tool, so
  the fixed name is freed at once). No file under `%LOCALAPPDATA%\veyq`, `%TEMP%` or `INetCache` held either Secret Key,
  `veyqkit:` or `%PDF-` (the scan bitten on the saved kits); `Recent` held a `.lnk` naming each kit the pickers saved or
  opened (accepted above, the save dialogs' records; a save picker's own `.lnk` measured on the Memory's download). A
  defect was fixed on the way: a control kept across redraws was put into each draw's new row while still the old
  row's child, so every draw after the first threw and the profile stopped drawing once a picture was chosen (the
  password, address and erasure pages held the same pattern). Two findings are #1009 and #1010.
- **What the macOS pass proved** (2026-11-19, the coordinator's main session with full-screen control, the ad-hoc debug
  build, a throwaway with a member on its own tenant): a first name and a picture chosen through the open panel, framed
  and saved, the shell's avatar showing it; *Change password* (current, new twice, the Secret Key) done; *Change sign-in
  address*'s request sent and its kit made, *I saved it* off until `NSSavePanel` had saved it (named for the old address,
  #1010); *Secure your account* — *Secure* off until the kit was saved and confirmed, the destructive confirm, committed:
  on relaunch the old Secret Key refused (*The Secret Key is not right.*) and the new kit, opened through *Open
  Emergency Kit*, and the password signing in, the Memory and its photos read back; *Erase account* offering the last
  owner's choice, the space handed to the member, the app back at the sign-in with no record of the address left in
  `~/Library/Application Support/app.veyq` and `offline/` empty. After two kit saves neither form of the Secret Key (with
  and without dashes) was in any file under `~/Library/Application Support/app.veyq`, `~/Library/Caches` or `$TMPDIR`, and
  no kit file (`%PDF-`, `veyqkit:`) in the app's own folders. Two defects it found were fixed after it, unclicked: the
  crash at the rotation's commit (a gallery on screen indexed rows newer than its layout, `MemoryScreen.photoGrid`) and
  the Settings window left as an empty placeholder after the erasure (it now closes with its page); a click into a sheet
  form's later fields focused nothing (Tab did, #1030).
- **What no pass has proved yet.** The address change's confirm and the re-addressed sign-in live (the dev backend on
  the Mac the passes ran on sends no mail; `ReaddressTest` holds the re-address, `LiveCredentialFlowsTest` skips there
  and says so); a rotation begun on the web and finished by an app's unlock (held by `FinishRotationTest`); a safety
  code scanned between two apps and the rename of another named device (the throwaways had none). On macOS the rotation's
  done screen with each scope's new name, both safety-number sides and #917's other device (inbox #976); on Windows the
  MSIX's no-copy check and the rest #983 names. **The owner's:** iPhone and iPad (the
  sheets, `fileExporter` into Files, Face ID's re-enrolment, #975 — no simulator boots here, #928), Touch ID and Windows
  Hello for real after a password change and a rotation, an address change to a real mailbox with the link opened on a
  phone, and a safety code scanned between two phones with real cameras (#912).

## Who's who (inbox #967, built 2026-11-27)

Decision 3 gave a person's page its state; the web's tenant section *Who's who* (`peopleOverview.ts`,
`PeopleOverviewPanel.vue`; uc.spaces.enforce-verified-identities main flow 5) lists every other member's at once. The
apps now show it too (owner 2026-11-26), decided as follows:

1. **The overview is the core's, its rule one pure function.** `PersonTrust.whosWho()` does the web's reads — the
   tenant's members anew, each other member anchored as their page anchors them (the one write: the pin it moves), the
   pins once (`VeyqAccount.pinsOfAccount`), the verifications once and counted for the anchored alone, a key chain for a
   rotation's date only where the pin is not the chain's genesis at the served head. `WhosWho.assemble` is `overviewOf`:
   who is listed (not this account, not a pending proposal), the one state (`CANT_CONFIRM`, `VERIFIED`, `NOT_VERIFIED`,
   `UNCHECKED` for a check that failed for a request — never a false anomaly), *new*, rotated, replaced, and the order
   (anomalies first, then `NameOrder` over the folded names, then the id). **Load-bearing:** an anomaly's row takes no
   count, pin or rotation, so it never reads as verified whatever rows the server serves (`WhosWhoTest`, bitten by
   letting an anomaly through as anchored: red). A pin store this device refuses is said in the web's `verify.trust*`
   words, never read as nobody, with *Start this list over* beside them (`WhosWho.trustReset`, the web's `TrustResetLink`).
2. **The core says the words and whether to compare.** A row carries `stateWord`, `note`, `subtitle` (`@username`, the
   web's list) and `compare` (the web's: every row this account has not verified); the page's one line in place of a
   list is `said` with `retry`. The verifiers' names are joined "you" first, as every app's person page already joins
   them (the web joins in the rows' order). The row's type is `WhosWho.Other`: a second `Row` on the wire renames the
   existing one for C# and Rust (the bindings name a type by its last part unless two share it).
3. **Reached as on the web: Settings' tenant section, after the personal ones, before Terms** (`SettingsSection.WHOS_WHO`,
   `tenant-people`, every member's; `PlansAreTheWebsTest.theTenantSectionsAreTheWebsInTheirOrder`). Each platform opens
   a page over Settings in its own way — Android a full-screen dialog as Terms, iOS a list pushed in Settings' navigation
   stack, the Mac a sheet, Linux a subpage of the preferences dialog, Windows a `ContentDialog` — and a row and its
   *Compare safety numbers* open the person's profile, where the compare lives (the web's link). Settings closes for it:
   the profile is the shell's, so its back returns to the shell, not to Settings, which is no page of the shell's history.
4. **No Who's who on a TV** (ADR 0159/0160): a TV's settings are its own few, it shows no safety number and compares
   nobody; a list whose one action opens a compare the TV does not offer is no viewing.
5. **No move of the web's TypeScript** (ADR 0155 decision 11): no *on demand* row covers the overview; the web keeps
   `peopleOverview.ts`.

Consequences: the core anchors members one after another under the anchoring lock and writes each first sight's pin at
once — the web's `PinPass` (inbox #710, #741) has no port, so a cold large tenant costs more requests than the web's
(inbox #1414). Proved by `WhosWhoTest`, `PlansAreTheWebsTest` and, live as a throwaway with a member,
`LiveAccountFlowsTest.whosWhoListsTheOtherMemberAsTheirPageShowsThem`; the passes are native-apps § Built, by area.

## Members (built 2026-11-27)

The web's tenant section *Members* (`TenantMembersPanel.vue` over `members.ts`; uc.spaces.remove-tenant-member, ADR 0101
decision 9) is in the apps too (owner 2026-11-26: every app does what the web does). It was decided as follows:

1. **The roster and its acts are the core's** (`session/TenantMembers`, `TenantSession.members`). The roster lists the
   TENANT scope's members (`listScopeMembers`) in the server's order. Each real name opens on the device under the tenant
   key, bound to (tenant scope, member), as the directory's does (`People`). The owner is read from `listMyTenants`' role,
   as the web's panel reads it. A row offers only what the web's row offers: *Make co-owner* on a member, *Remove
   co-owner* on an owner, and *Remove member* never on an owner. All three are an owner's only. **Load-bearing:** a
   member's roster offers no act (`TenantMembersTest`, bitten by offering *Remove* on every row: red). A role change
   (`setTenantMemberRole`) moves no key, and the last owner's demotion is said as the web says it (`409 last_owner`). The
   removal (`removeTenantMember`) is the server's cascade. Its two opt-ins are the web's, unticked: turning off every link
   of the space is sent with it; the space's new lock runs only once the removal landed (decision 5). After it the
   session reopens what moved and finishes the re-keys now due (`reopenMoved`, then `resumeRekeys`), the web's
   `rekeyClosedScopes`.
2. **The role shows as a word** ("Owner", "Member"). The web's row prints the enum's code as it is. Each row carries the
   web's trust badge (`MemberBadge.of` over *Who's who*'s states, the web's `badgesIn`): *Verified* where a verification
   of the member's current head counts, *New* under two weeks after this account first saw them, the anomaly with its
   note; *not verified* and an unchecked member show none, nor does one's own row. **Load-bearing:** verified wins over
   new (`SpaceIconsTest.theBadgeIsTheWebs`, bitten). A group's page carries the same badge (the web's group panel draws it).
3. **Reached as on the web**: Settings' tenant category opens with it (`SettingsSection.MEMBERS`, `tenant-members`, before
   *Who's who*; `PlansAreTheWebsTest`). Each platform opens it the way it opens *Who's who*. A row's acts are its
   platform's menu: Android an overflow `DropdownMenu`, Apple a `Menu` and a context menu, Linux a `GtkMenuButton`,
   Windows a `MenuFlyout`. The removal's question is an alert, a sheet on Apple, and on Windows it is asked in place.
   The bridge carries `Settings.Members`, `Settings.MemberRole` and `Settings.MemberRemove`.
4. **Leaving a space is no gap**: the web has no self-leave (no operation in `scopes` or `tenants`, no use case), and a
   member leaves only when an owner removes them. The panel's status lines — the re-keying and resealing hints and a
   stuck compromise rotation's notice — stay the web's (native-apps § Open).
5. **The space's new lock and turning off every link are one core seam for a tenant and a group** (`session/Locks`;
   ADR 0025 § 2, ADR 0075 point 5), each an owner's section below the roster behind its question. The new lock is the web's
   `rekeySpace`: `commitRotation` over the directory read now, then the nodes re-keyed on the device at once
   (`reopenMoved`, `resumeRekeys` awaited, the web's `rekeyScope`); the notice says the lock is in where this scope's
   re-key finished, else that it is going in. After a tenant removal it runs with the web's `excluding` (added to
   `commitRotation`): the removed member is dropped from the list and every re-read by the owner's choice, never sent as
   a `removesUserId`, which a TENANT refuses. **Load-bearing:** the new epoch is wrapped to no excluded or removed member
   (`ScopeRotation.listedFor`, `SpaceIconsTest`, bitten). Turning off every link is `revokeScopeShares`, the count said
   (none, one, more), each close's re-key started in the background. The bridge carries `Settings.MembersNewLock`,
   `Settings.MembersRevokeLinks` and `Settings.MemberRemove`'s `newLock`.
6. **A space's icon is opened, set and removed as the web's** (`session/SpaceIcons`, `ScopeIcons`,
   `TenantSession.icons(encoder)`; uc.spaces.set-space-icon): on the Members header and a group's page, every member seeing
   it, an owner setting and removing it. The platform crops the picture square as for one's own picture
   (`ProfileModel.EDGE` RGBA; the bridge asks the host's `PictureSquare`), the core encodes it by the profile's ladder,
   pads it to 16 KiB and seals it `space-icon` under a fresh value key wrapped at the epoch it holds; a `409 stale_epoch`
   reads the scopes anew and seals once more. A deleted group's icon opens from its own grant. **Load-bearing:** an icon
   opens at its own scope, icon epoch and key epoch only, held to a fixture the web's `spaceIcon.ts` wrote
   (`SpaceIconsTest`, bitten). The bridge carries `Settings.Icon`, `Settings.IconSet` and `Settings.IconClear`. The
   switcher and the drawer draw no space picture yet (native-apps § Open).
7. **The join takes an optional picture** (`TenantJoin.choosePicture`, ADR 0084): below the name on the account step,
   never at a handover (the web's `JoinPicturePicker`), kept encoded in the flow only and sealed `member-avatar` beside the
   name at the accept, the register's and the sign-in's alike; zeroed at the finish and the close. **Load-bearing:** the
   owner opens the joiner's picture (`InvitesLiveTest`, bitten by sending no `sealedAvatar`). The bridge carries
   `Join.Picture`, `Join.RemovePicture` and `Join.PictureBytes`.

Consequences: proved by `TenantMembersTest`, `PlansAreTheWebsTest`, the bridge's `WireTest`, `SecretFreeSchemaTest` and
`DispatchTest.theTermsEditorsChangesAreTheCoresDraftAndTheMembersCallsNeedATenant`, `SpaceIconsTest`, and, live as a
throwaway with a member, `MembersLiveTest`, `MembersPageRestLiveTest` (icons, both new locks, the links, the removal's new
lock) and `InvitesLiveTest` (the join's picture). The passes are in native-apps § Built, by area.

## Groups (built 2026-11-27)

The web's groups (en.spaces.group-scope; uc.spaces.add-group-member, uc.spaces.consent-group-add,
uc.spaces.manage-group-members, uc.spaces.rename-group, uc.spaces.delete-group) are in the apps too (owner 2026-11-26).
It was decided as follows:

1. **A group's name opens on the device** (`session/GroupNames`): sealed (`group-name`) under a value key of its own
   whose wrap the group key holds at the address of `group-name` in the SEAL schema (ADR 0115); a pending consent invitee
   is handed that key (`group-name-key`). Held to a fixture the web's `@veyq/crypto` wrote (`GroupNamesTest`). Every open
   GROUP scope is named by it, so the switcher, the drawer and Home name groups (`ShellWords.space`).
2. **The acts are the core's** (`session/TenantGroups`, `TenantSession.groups`): *Create group* where `core.group-space`
   is on; *Rename* under a fresh value key, sealed to each consent invitee the tenant anchors and rebuilt once on a stale
   read; *Delete* and *Restore*; a group's page with the web's row acts; *Add member* by username, anchored first (an
   anomaly sends nothing), a proposal where the member asks first; roles; *Remove* by the group's removal-rotation — the
   core's `ScopeRotation.commitRotation` gained the web's `removes` and `revokeLinks`; *Withdraw* a proposal; the caller's
   invitations with *Accept* and *Decline*. **Load-bearing:** only an owner's page offers an act, and *Remove* never on an
   owner, the caller or a proposal (`TenantGroupsTest`, bitten); a removal without `removes` leaves the member
   (`GroupsLiveTest`, bitten).
3. **Two Settings sections, after the tenant's** (`SettingsSection.GROUPS`, `GROUP_INVITATIONS`): the web's `groups`
   category holds a section per owned group and its invitations sit in the sidebar; the apps' page is a fixed plan over one
   tenant, so *Groups* lists the caller's groups and opens each one's page, and *Group invitations* is the inbox
   (`PlansAreTheWebsTest`). The bridge carries `Settings.Group*`.
4. **A group's picture, new lock and links are the tenant's** (§ Members decisions 5 and 6): the page's header shows its
   icon, and an owner's *Links* and *New lock* sections show while the group is not being deleted
   (`GroupPage.wholeActs`, the web's `!isPending`). A deleted group's page and row say the day it goes
   (`GroupPage.deletion`, `GroupRow.deletion`, `groups.pendingDeletion`). A group's removal needs no new-lock option: it
   rotates by itself.

Consequences: proved by `GroupNamesTest`, `TenantGroupsTest`, `ShellWordsTest`, `PlansAreTheWebsTest`, the bridge's
`WireTest` and `DispatchTest`, and live as a throwaway with its member by `GroupsLiveTest`. The passes are in
native-apps § Built, by area.

## The task queue's jobs (inbox #966, built 2026-11-28)

The web's workers of the operator-blind task queue (ADR 0021; `grantDeliveryWorker.ts`, `scopeRotationWorker.ts`;
uc.spaces.complete-queued-rotation) run in the apps too (owner 2026-11-26), so a person who uses only an app no longer
leaves a queued rotation (a proposal declined or withdrawn, a member removed from the tenant while sharing a group) or a
behind member's grant undone. It was decided as follows:

1. **The rules are the core's** (`session/tasks/TaskQueue.kt`): `TaskPass` ports both workers case for case — a
   delivery anchored in the scope's tenant (an anomaly declined `identity_unanchored`, a refused pin store pausing the
   scope and declining nothing), sealed to the target at the epoch held and completed stating it, `409 stale_epoch` /
   `recipient_key_not_current` ending the scope; a rotation re-keyed and completed where an earlier lease committed it,
   else the directory read **after** the claim and the next epoch committed (`ScopeRotation.commitRotation`, removing and
   excluding nobody), the nodes re-keyed, the job completed, its lease renewed every 30 s; `stale_epoch` and
   `not_scope_owner` declined, any other failure failed back (`rotate-failed`), a job of another tag failed back. The
   capability tags are the registry's (`TaskTagsTest`).
2. **Claimed per open shared scope, the scope named.** The web delivers for its one active scope and claims rotations
   account-wide; the apps have no active scope, and a rotation claimed account-wide may lie in a tenant this device has not
   open, whose key it cannot use (failing it spends an attempt). So each open TENANT and GROUP scope is claimed with its id
   — `grant-delivery`, and `scope-rotation` where this account owns it, in one claim —, one request per scope and pass; a
   PERSONAL scope is never claimed. A rotation in a tenant no device of the owner opens waits, as on the web it waits for a
   tab on that tenant.
3. **While the tenant is open, every 45 s** (the web's poll): started where the tenant is shown (`AccountDoor.opened`,
   beside the re-key resume), stopped by `TenantSession.close()`, each pass only while the account is online, not opened
   offline and not closed. **Never in an OS-scheduled background run** (`BackgroundRun`, ADR 0153 decision 8): a job is the
   open tenant's work, as on the web the open tab's; a phone in the background runs on only as long as its process is
   left. No platform code: nothing is shown, as on the web.

Consequences: a tenant with many groups sends one claim per group every 45 s while it is open (a `204` without a body).
Proved by `TaskPassTest` (every rule above bitten: an anomaly sealed, a refusal declined, a stale epoch drained on, a key
copy left unzeroed, a member claiming rotations, a stale rotation failed, no renewal, a pass while not working, a stop
that keeps the loop — each red) and `TaskTagsTest`, and live as a throwaway with its member by `LiveTaskQueueTest`
(`:composition`: a member removed from the tenant while in the owner's group; the queued rotation completed by the
owner's core, the group at its next epoch; bitten by leaving the commit out: red).

## The moderation key (inbox #965, built 2026-11-28)

The two places the web's account flows touch the instance moderation key (ADR 0099 §§ 3–5, ADR 0101 decision 9) are in
the apps too (owner 2026-11-26), so decision 9's *Do this on the web* is gone. The apps do not moderate: no case is opened,
no moderation sign-in, no review (inbox #1014). It was decided as follows:

1. **The bundle is the suite's** (`V1.moderationBundle`, `moderationSeed`): HKDF-SHA256 of the 32-byte `M_n` under
   `veyq/v1/moderation/kem|sign`, the X-Wing and Ed25519 public halves; held to the `moderation_key` vector by the Kotlin
   conformance run (now checked: the two seeds, the bundle, another length refused) on the JVM, Apple's libsodium and
   `@veyq/core-js`. The seal is `moderation-grant` [epoch], already held by `seal.asym`.
2. **A holder's key replacement re-seals their grants** (`session/ModerationKeyStage`, the web's `ModerationKeyStage`):
   after the rooted stage, every live epoch the account holds a grant of (`listMyModerationGrants`, `403 not_moderator`
   = none) is opened with the OUTGOING identity, checked against the epoch's published bundle and sealed to the new
   identity — or declared (`sealedGrant: null`) where it does not open or the bundle disowns it — and staged
   (`putRotationStageModerationGrants`, 256 at a time); a re-read after `409 rotation_plan_changed` stages only what
   moved, a fresh stage everything. Each `M_n` is zeroed after its seal.
3. **The last holder's erasure hands the key to the Server Owner** (`ModerationGrants.sealForServerOwner`, the web's
   `sealForServerOwner`): only where the erasure preview names a `serverOwnerDelivery`; the Server Owner anchored by the
   served head with no tenant (an anomaly or a refused pin store: *could not verify*, nothing sent), this account's grants
   opened and checked (a mismatch opens nothing), each named epoch sealed to the Server Owner (one not held: *could not
   open every key*), the upload riding in the erasure request itself — a refused erasure reappoints nobody. The web's
   note shows above the password (`AccountErasure.State.handsOver`), and the web's refusals are said
   (`moderation_delivery_required`, `identity_changed`). A holder who is not the last one erases as anyone does.
4. **The platforms draw only the note**: Android a tonal surface, Apple a labelled section, Linux a heading and its
   text, Windows an informational `InfoBar`, each between the last-owner choices and the spaces deleted along, as the
   web places it. The *on the web* steps and their words are removed from the core and the four apps.

Consequences: proved by the conformance run's `moderation_key` (bitten: another HKDF label, red), `ModerationGrantsTest`
(over real seals: a re-seal only where it opens and its bundle is its own, the stage's delta, chunks and fresh start,
the hand-over only for held epochs and nothing opened on a mismatch — each rule removed in turn red) and
`CredentialFlowsTest` (the note, the upload in the request, a refused hand-over sending nothing, the server's refusals —
bitten by dropping the upload and by swallowing the refusal). **Not proved live**: no throwaway account can be a
moderator (inbox #992), and appointing one is the Server Owner's act on the principal; the stage's wiring into the
rotation and the hand-over against a real server wait in inbox #1426. A non-moderator's rotation and erasure ran live as
throwaways (`LiveRotationTest`, `LiveErasureTest`, 2026-11-28); `LiveRotationTest` had failed in `:core` since the module
split (`AppKinds read before the applications were installed`, inbox #1191) and now installs every application inert
(`TestApplications.installInert`).

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
