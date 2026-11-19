---
id: cr-app-account
title: The web's account features in the apps — devices renamed, one's profile, safety numbers, the password, the address, the keys replaced with a saved Emergency Kit, the account erased — the core's ports held to the vectors the web answers to
status: planned
satisfies: []
adrs: ["0148", "0003", "0023", "0028", "0058", "0060", "0074", "0084", "0101", "0111", "0115", "0127", "0136", "0137", "0140", "0123"]
invariants:
  - "operator-blind E2EE: the apps send the server exactly what the web sends for each operation (the same operationIds, the same sealed fields, the same proofs); no new endpoint, no new field, the backend unchanged"
  - "every key-touching rule is the core's, a port of the web's flow, and every byte it signs, seals or derives is held to contracts/crypto/vectors/v1.json by crypto/Conformance.kt on the JVM and on libsodium (Apple, Linux, Windows) — the sections the core does not read today (secret_key_proof, auth_attestation, rotation_stage, verification, fingerprint's digits and chain_code) are read before any flow uses them"
  - "the Secret Key, the password and the Master Keys (old and new) live in the core's memory only for the call and are zeroed in a finally; none crosses the bridge outward (SecretFreeSchemaTest), none is logged, none is written to disk"
  - "the one plaintext secret the apps ever write is the Emergency Kit PDF, written by the core straight into the file the person chose in their platform's save dialog — no temporary copy, no app-owned copy, no share sheet; held by a no-copy check per platform"
  - "a platform draws the core's state and decides nothing (ADR 0136); no word but from src/mobile/words/words.json, the web's copy where the web has it"
  - "no pass of this CR changes, rotates or erases the seeded principal or the seeded member: every write runs as a throwaway account in a throwaway tenant, created and erased by the pass (§ Test data)"
---

## Goal

Block F of the V2 order (inbox #863, #949, and native-apps.md § Open's "device register's own screen"): the apps hold
only what surrounds signing in — the address, this device, devices and sessions, saved keys, add a device, storage,
map services. The web also lets a member rename any of their devices, edit their per-tenant profile (username, real
name, picture, the group-add consent), see and compare safety numbers, change the password, change the address, replace
the keys with a new Emergency Kit, and erase the account. This CR brings each to the four apps **as the web's**: the
web's flows ported once into the Kotlin core, held to the shared vectors, drawn by each platform after its guideline.

## Context & constraints

- **Read first:** ADR 0060 and 0111 (the rotation; decisions 6–13, 20–22), ADR 0003 (auth key, login challenge),
  ADR 0074 decision 8 (address change), ADR 0101 (erasure), ADR 0084 + 0115 + 0127 (profile picture, value keys, WebP),
  ADR 0028 decision 3 + 0104 (safety numbers, pins), [en.account.device-register](../docs/features/account/enablers/en.account.device-register.md),
  ADR 0058, ADR 0140 decision 2 (the store's header under the Master Key), ADR 0136, ADR 0137 (files by path);
  `src/mobile/CLAUDE.md` § Secrets on screen and § Nothing on disk; the web's flows named in R2–R8.
- **The web is the reference implementation.** Where this CR differs it says so and why (D9's moderators, D7's store).
- **Where what runs** (from `src/mobile/`): `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :core:jvmTest
  :core:macosArm64Test :bridge:jvmTest :android:assembleDebug`; the Apple builds `xcodebuild -project
  apple/Veyq.xcodeproj -target Veyq -sdk macosx build CODE_SIGNING_ALLOWED=NO` (and `-sdk iphonesimulator`) after
  `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `/usr/bin/python3 linux/run.py test|bridge|build|run`,
  `/usr/bin/python3 windows/run.py test|bridge|build|run` (over `VEYQ_WINDOWS_SSH`); live tests with
  `VEYQ_LIVE=http://veyq.localhost:8080` and **`VEYQ_ENV_FILE=<a throwaway's env file>`** (§ Test data); the web
  `pnpm run check` in `src/web/` and `src/packages/crypto/`.
- **Machine hints (hold for the planned shape; re-derive them):** the iOS simulators do not boot under this Mac's Xcode
  (#928) — iPhone and iPad passes are the owner's; the macOS build runs here. Linux passes in the Fedora container
  through Broadway; Windows on the ARM64 VM through UI Automation. Gradle needs `--rerun` per task when only the
  environment changed (`src/mobile/CLAUDE.md` § checks).
- **Out of scope** (each its own row or block): recovery with the Recovery Kit and "forgot password" (the web's
  `/recover`; an account that cannot sign in has no app session to start from — the apps' sign-in links to the web's
  page as today), terms, invites and members (native-apps.md § Open), the Settings → Shared space → *Who's who*
  overview (#967), the moderation key in the apps (#965), the task queue's jobs (#966), a received link — the address
  change's included — opened in the app (block H, #435).

## Measured at refine (2026-11-18)

Branch `claude/block-f` at `d663f034f`, clean before this CR. `C=src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile`,
commands from the repo root.

| # | What | Where / command |
|---|---|---|
| R1 | **The apps call none of the operations.** No caller of `changePassword`, `requestEmailChange`, `confirmEmailChange`, `rotateAccountKeys`, the rotation's plan/stage operations, `getOwnAccountErasurePreview`, `eraseOwnAccount`, `getMyProfile`, `updateMyProfile`, `setMyAvatar`, `clearMyAvatar`, `listVerifications`, `putMyVerification`; all are in `contracts/api/account.openapi.yaml` / `tenants.openapi.yaml`. | `grep -rnE 'changePassword\|rotateAccountKeys\|eraseOwnAccount\|requestEmailChange\|confirmEmailChange\|updateMyProfile\|setMyAvatar\|putMyVerification\|listVerifications' --exclude-dir=build src/mobile` → nothing; `grep -n "operationId: \(changePassword\|requestEmailChange\|confirmEmailChange\|rotateAccountKeys\|eraseOwnAccount\|getOwnAccountErasurePreview\|updateMyProfile\|setMyAvatar\|clearMyAvatar\)" contracts/api/account.openapi.yaml` |
| R2 | **The device register is read, reconciled and written by the core; the apps rename only this device.** `DeviceRegisterStore.rename(deviceId, label, now)` takes any device id; `AccountSettings.rename` passes `door.deviceId` only. The web renames the device of any family (`renameDeviceOfFamily`, last write wins) in the same "Devices and sessions" panel — **the web has no separate register screen**; the families named from the register and the devices with a saved key are already on all four apps. No screen anywhere removes a register entry (the enabler's § A user-intended removal). | `grep -n "fun rename" $C/session/DeviceTrust.kt $C/session/AccountSettings.kt`; `sed -n 415,431p src/web/src/features/account/deviceRegister.ts`; `grep -n "renameDeviceOfFamily" src/web/src/features/account/sessions/SessionsPanel.vue`; `sed -n 106,110p docs/crosscutting/native-apps.md`; `sed -n 472,474p docs/crosscutting/native-apps.md` |
| R3 | **The per-tenant profile** is the web's Settings → *Your profile* (`ProfilePanel.vue`: username, real name, picture, the account-global "ask before adding me to groups") and, the same fields, *Edit profile* on one's own `#people/<myId>` (`PersonProfile.vue` `isMe`, `NamePictureFields`, `useNameAndPicture`: a `409 stale_epoch` re-seals, K_tenant zeroed on dispose). Name and picture are value-key seals (`member-name`, `member-avatar`, K_v wrapped under the tenant key at its epoch, ADR 0115); the picture a 256 px WebP padded to 16 384 bytes (`sealedAvatar` ≤ 21 908 base64 chars). The core opens both (`People.realName`, `MemberAvatars`) and writes value-key seals elsewhere (`ShareLinks`). | `sed -n 1,6p src/web/src/features/account/profile/ProfilePanel.vue`; `grep -n "AVATAR_EDGE\|AVATAR_PADDED_LEN" src/web/src/features/account/profile/memberAvatar.ts`; `grep -n "maxLength: 21908" contracts/api/account.openapi.yaml`; `grep -n "member-name\|member-avatar" $C/crypto/Contexts.kt` |
| R4 | **No WebP encoder in the apps.** The core builds libwebp from the web encoder's pin for its bridged targets and decodes with it (`WebpPicture`); nothing encodes. The JVM (Android) target has no libwebp. A cover's 16:9 pan-and-zoom is the core's `CoverFrame` (the web's `ImageCropper` math), drawn on every platform. | `grep -rn "WebPDecode\|WebPEncode" src/mobile/core/src src/mobile/core/webp`; `sed -n 1,12p $C/session/CoverFrame.kt`; `grep -n "bridgedSlices\|jvm()" src/mobile/core/build.gradle.kts` |
| R5 | **Safety numbers:** `fingerprintDigits` (6 groups of 5 digits over `fingerprintHash`), `chainCodeDigits` (2 groups over the key-chain head), the `veyqid:2:` QR payload (`safetyCode.ts` `parseSafetyCode`, `compareScan`), the state of a person (`PersonSafetyNumber.vue`: unverified, verified, verified by, new, rotated, replaced, anomaly) and `verifyPerson` (a `member-verification` record sealed under the tenant's current epoch, `putMyVerification`). The core has `fingerprintHash`, `keyChainHead`, `Anchoring` (FIRST_USE, MATCH, ADVANCED, REPLACED, ANOMALY), the pairing's check number; no digits, no chain code, no `veyqid` scheme, no verification read or write. Its conformance checks the fingerprint hash, not `digits`/`chain_code`. | `grep -n "export function fingerprint\|chainCodeDigits" src/packages/crypto/src/v1.ts`; `grep -n "veyqid" src/web/src/features/account/safetyCode.ts`; `grep -n "ALL" $C/qr/QrSchemes.kt`; `grep -n "fingerprint\|digits\|chain_code" $C/crypto/Conformance.kt` |
| R6 | **Password and address change** (ADR 0060 decision 2, ADR 0074 decision 8): the cold proof `proveColdCredential` (the PASSWORD wrap opened with the typed password and Secret Key must give the vault's Master Key), an attestation by the current auth key, a Secret-Key proof signature; a password change re-wraps the same Master Key under a fresh salt, the server deletes every wrap but PASSWORD and RECOVERY (this device's saved key with them) and revokes every other family; an address change re-derives the auth key under the new address's salt, waits for the mailed link (`#token`, opened in the same account's session, 24 h), revokes the other families on confirm, and the web offers a new kit afterwards. | `sed -n 51,104p src/web/src/features/account/changePassword/changePassword.ts`; `sed -n 35,88p src/web/src/features/account/email/emailChange.ts`; `sed -n 58,75p src/web/src/features/account/coldCredential.ts`; `sed -n 25,39p src/backend/core/src/main/kotlin/me/raddatz/veyq/core/identity/WrapMethods.kt` |
| R7 | **Replacing the keys** (ADR 0060, 0111): `rotateKeys.ts` (904 lines) mints a new identity, Master Key and Secret Key, signs a key-chain step, stages self-grants in chunks of 256 plus rooted invites, join endorsements and **moderation grants**, re-seals the pins and the register, publishes with a doubly signed proof; `afterCommit` moves the trust marks, re-seals the node pins and each application's Master-Key material (`registry.masterKeyRotated`); `finishRotation.ts` mints each scope's next epoch (`commitRotation`) from any device of the account that unlocks — the web every 45 s while unlocked. The core re-keys due nodes (`NodeRekey`) but has **no `commitRotation`, no `finishRotation`, no task-queue worker**. | `wc -l src/web/src/features/account/rotation/*.ts`; `sed -n 855,880p src/web/src/features/account/rotation/rotateKeys.ts`; `sed -n 1,8p src/web/src/features/account/rotation/finishRotation.ts`; `grep -rn "commitRotation\|rotateScope\|listRotationScopeStates" $C` → nothing |
| R8 | **The Emergency Kit on the web is never shown — only saved.** `EmergencyKitSave.vue` offers *Download* (jspdf: one A4 page, the address, the Secret Key in Courier, three address fields, the QR of `kitPayload`, the token in the PDF's `Keywords`), then a checkbox "I saved it" enabled only after the download; the rotation's publish waits for both. There is no print button. The core **reads** kits (`EmergencyKit.read`, `KitImport`, `KitPdf`) and writes none; it has a QR encoder (`QrEncoder`, `QrPng`); nothing in the apps writes a PDF or prints. The web's kit fixtures are read by the core's `KitFixtureTest`. | `sed -n 1,25p src/web/src/features/account/emergencyKit/EmergencyKitSave.vue`; `sed -n 156,172p src/web/src/features/account/emergencyKit/emergencyKit.ts`; `sed -n 19,23p $C/crypto/EmergencyKit.kt`; `ls $C/qr`; `grep -rliE "PdfDocument\(\)\|UIGraphicsPDFRenderer\|PdfSurface\|PrintManager\|NSPrintOperation" src/mobile --include='*.kt' --include='*.swift' --include='*.rs' --include='*.cs'` → nothing; `sed -n 5,8p src/mobile/core/src/jvmTest/kotlin/me/raddatz/veyq/mobile/account/KitFixtureTest.kt` |
| R9 | **Erasure** (ADR 0101): a password-only attestation over `[userId, nonce]` (no Secret Key), the preview's last-owner tenants (delete along or name a new owner), the Server Owner refused (`409 server_owner_account`), the **last moderation-key holder** must deliver it to the Server Owner (`serverOwnerDelivery`, `moderation-grant` seals), all sessions end, the purge after 14 days; the web then clears its own records. The core holds no moderation key. | `sed -n 69,124p src/web/src/features/account/erasure/eraseAccount.ts`; `sed -n 1606,1614p contracts/api/account.openapi.yaml`; `grep -rli moderation $C \| grep -v "Report\|NodeReport\|Contexts\|search\|NodeHeader\|ShellModel\|MusicModel"` → nothing |
| R10 | **Vectors.** `contracts/crypto/vectors/v1.json` holds `secret_key_proof` (rotation, change_password, recovery, change_email), `auth_attestation` (change_email, erase_account — **no change_password case**: it signs the new key's raw bytes), `rotation_stage`, `verification`, `fingerprint` (hash, digits, chain_code), `emergency_kit_token`, `kek`, `auth`, `kdf_params`, `key_chain`, `seal.sym` (master-key, identity-priv, device-register, identity-pins), `field_seal` (member-name, member-avatar). The core's `Conformance.kt` reads none of `secret_key_proof`, `auth_attestation`, `rotation_stage`, `verification`; no vector pins the `veyqid:2` safety-code payload. | `/usr/bin/python3 -c "import json;d=json.load(open('contracts/crypto/vectors/v1.json'));print({k:list(d[k]) for k in ['secret_key_proof','auth_attestation','rotation_stage','verification','fingerprint']})"`; `grep -c "secret_key_proof\|auth_attestation\|rotation_stage\|\"verification\"" src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Conformance.kt` → 0 |
| R11 | **The store and the marks hang on the Master Key.** The offline store's `K_off` is wrapped under `HKDF(masterKey, "veyq/v1/offline-store")`; a header that does not open under the unlocked Master Key is wiped (ADR 0140 decision 2). Trust marks have a slot per Master Key (`TrustMarks`). | `sed -n 62,67p docs/decisions/0140-*.md`; `grep -n "slot" $C/session/TrustMarks.kt` |
| R12 | **Settings and the person page today:** four Settings pages (Android `SettingsScreen.kt`, Apple `SettingsView.swift`, Linux `account_settings.rs`, Windows `AccountSettingsPage.cs`) over `AccountSettings`, the search's `SettingsSection` enum naming each section; four person pages (`PersonScreen.kt`, `PersonScreen.swift`, `person_page.rs`, `PersonPage.cs`) over `PersonProfile`, which says own-page editing and safety numbers are not in it (#949). Open bugs on the pages this CR grows: Linux's page jumps on a redraw (#957), macOS's rename alert is empty after its first opening (#917). | `wc -l src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/SettingsScreen.kt src/mobile/apple/Veyq/SettingsView.swift src/mobile/linux/src/account_settings.rs src/mobile/windows/Veyq/AccountSettingsPage.cs`; `sed -n 15,24p $C/search/SettingsSection.kt`; `sed -n 10,14p $C/navigation/PersonProfile.kt` |
| R13 | **Saving a file:** the core writes to what the dialog gave (`FileSink`, ADR 0137): Android `CreateDocument`, macOS `NSSavePanel`, Linux `gtk::FileDialog::save_future`, Windows `FileSavePicker`; **iOS writes into `AppModel.downloadDir()` and then moves it with `fileMover`** — an app-owned copy first, wrong for a kit. | `grep -rn "CreateDocument\|NSSavePanel\|save_future\|PickSaveFileAsync" src/mobile/android/src src/mobile/apple/Veyq src/mobile/linux/src src/mobile/windows/Veyq \| head`; `sed -n 304,306p src/mobile/apple/Veyq/NodeHeader.swift`; `grep -n "downloadDir" src/mobile/apple/Veyq/NodeHeader.swift` |
| R14 | **Test accounts on the dev instance.** The seeder births one principal (Server Owner and the dev tenant's owner) and one member, and refuses a set-up instance; registration is only by invite (`registerAndAcceptTenantInvite`); `createTenant` on the admin listener (`:8090`) mints an owner invite; `DevSecondTenantTest` already gives the principal a second tenant through the admin listener; Mailpit is up through the launcher (UI/API `127.0.0.1:18025`), and the seeder points a tenant's mail at it. `eraseOwnAccount` refuses the Server Owner. No native live test makes an account; `LiveAccountSettingsTest`'s "sign out every other" already demands a throwaway's env file (`VEYQ_LIVE_OTHERS_ENV`). | `sed -n 110,128p src/web/scripts/bootstrap-user.mts`; `sed -n 180,224p src/web/scripts/bootstrap-user.mts`; `grep -n "operationId: createTenant\|operationId: registerAndAcceptTenantInvite" contracts/api/tenants.openapi.yaml`; `sed -n 97,108p infra/docker/docker-compose.yml`; `sed -n 678,680p src/mobile/CLAUDE.md`; `sed -n 27,29p src/mobile/core/src/jvmTest/kotlin/me/raddatz/veyq/mobile/session/LiveAccountSettingsTest.kt` |

## Decisions

Phase 1 writes D1–D12 as a new ADR, [0148](../docs/decisions/0148-the-apps-account-the-webs-flows-ported-into-the-core-the-kit-written-by-the-core-into-the-file-the-person-chose.md) ("The apps' account: the web's flows ported into the core, the kit written by the
core into the file the person chose"); each "rejected" is its Alternatives.

### D1 — The device register's screen is the sessions section the apps have; it gains the web's rename of any device

R2: the web has no register screen beyond *Devices and sessions*, which the apps already draw from the register.
What is left is the rename: each row named from the register gets *Rename* (the web's `renameDeviceOfFamily`: the
family's device, last write wins, the copy says it renames every session of that device). Core: `AccountSettings.renameFamily(familyId, label)`
over `DeviceRegisterStore.rename`; a family the register cannot name offers no rename (the web's no-op). native-apps.md
§ Open's line is replaced by what is built. **No screen removes a register entry**, as on the web (the enabler's
union merge and eviction stay the rule; the passes' orphans are D12's to avoid, not a screen's to clean).
Rejected: **a separate "Trusted devices" screen** — a second list of the same families, which the web does not have.

### D2 — One's profile: one core model behind two places, as the web

`ProfileModel` (core, `session/`): reads `getMyProfile` for the **current tenant**, opens the name with the tenant key
(`People.realName`'s opener), holds the username, the name, the picture and `requireGroupAddConsent`; saves as the web
(`updateMyProfile` with `sealedName` + `epoch`, then `setMyAvatar`/`clearMyAvatar`; a `409 stale_epoch` re-opens the
tenant key and seals again once; `409 username_taken` a field refusal); the tenant key copy is zeroed when the model
closes; without a tenant grant the name and picture are disabled with the web's hint. Settings gains *Your profile*
(the web's rubric); one's own person page gains *Edit profile*, which opens the same model (ADR 0084 decision 6). After a
save the shell's people are re-read (`readPeople`), so every avatar and name updates.

**The picture:** the platform picks an image (its own picker) and draws the existing cover cropper with a **square**
frame (`CoverFrame` with `frameWidth == frameHeight`, zoom 1–3); the host renders the crop to 256 × 256 RGBA (as it
renders a cover's tiers, `Media.Tiers`); the **core** encodes WebP, steps the quality down to the web's budget, pads
to 16 384 bytes and seals (`member-avatar`). The encoder: libwebp's from the web's pin (`core/webp/build_libwebp.py`
gains the encoder's sources) on Apple and the bridged targets; on Android, whose core target is the JVM, the host's
`Bitmap.compress(WEBP_LOSSY, q)` behind the same `WebpEncoder` seam, the quality stepping still the core's. **Measure
first** (phase 3e): that the pinned libwebp's encoder builds for the Apple slices and the bridged ones; if it does not
for one, that platform's host encoder stands behind the seam and the ADR says which. No vector pins an encode (two
encoders never agree byte for byte); what is pinned is the seal (`field_seal.member-avatar`, already read by the core)
and the padded length, and the web opens an app-made picture (phase 3e's live test reads it back through the web's
`openMemberAvatar` rule ported in `MemberAvatars`).
Rejected: **Apple's ImageIO** — it decodes WebP but writes none. Rejected: **a PNG picture** — the contract's padded
length and every reader expect WebP (ADR 0127).

### D3 — Safety numbers: the core's port, the state the web shows, the compare by scan or by digits

Core `trust/SafetyNumbers` ports `fingerprintDigits`, `chainCodeDigits`, `safetyCodePayload`, `parseSafetyCode`,
`compareScan` (verdicts foreign, outdated, mismatch, match); `QrSchemes` gains `veyqid`. `PersonTrust` (core) gives a
person page its state — the web's set: not verified, verified, verified by {names}, new (under 14 days), rotated,
replaced, can't confirm — from `Anchoring` and `listVerifications` (opened under the tenant key, `member-verification`);
`verify(person)` ports `verifyPerson` (the statement signed by this identity at its head, sealed under the tenant's
current epoch, `putMyVerification`). One's own page shows *Your safety number* → the digits, the chain code and the
QR (the core's `QrEncoder`); another member's page shows the state and *Compare* → *Scan their code* (each platform's
existing scanner, ADR 0136's kit scan path, accepting `veyqid:` only) or *Compare digits* → "They match" / "They don't
match". A safety number is no secret: no secure window. Words: the web's `safety.*` and `trust.*`.

### D4 — The vectors first: the core reads every section its flows sign, and the gaps are closed in `contracts/`

Before any flow: `Conformance.kt` gains `secret_key_proof` (all four statements), `auth_attestation` (change_email,
erase_account), `rotation_stage`, `verification`, and `fingerprint`'s `digits` and `chain_code`. `contracts/crypto/vectors/v1.json`
gains, generated by the independent Python implementation as its neighbours are (the `note` says so): an
`auth_attestation.change_password` case (the raw new auth public key signed by the current auth key — today pinned only
indirectly) and a `safety_code` section (the `veyqid:2` payload from a hash and a head, and the parse verdicts: foreign,
outdated `veyqid:1`, a malformed one, mismatch, match). `src/packages/crypto`'s and the web's tests read both new parts,
so neither implementation is the other's test. The kit's PDF is no vector (D5's fixtures hold it).

### D5 — The Emergency Kit: written by the core, saved where the person chose, never shown — as the web

- **What:** `KitWriter` (core, `account/`): `kitPayload` ported (held to `emergency_kit_token`'s cases in the writer's
  direction) and a one-page A4 PDF of the web's layout — the address, the Secret Key in three lines of Courier, the
  three address fields as AcroForm text fields (the first holding this server's origin), the QR of the payload drawn as
  filled rectangles (no image), the token in the `Keywords` — with the PDF's standard 14 fonts (nothing embedded),
  no creation date (deterministic bytes), the kit's words from `words.json` (the web's `kit.*` print strings, the
  person's language). File name the web's: `veyq-emergency-kit-<email>.pdf`.
- **Cross-read proof:** the core writes `kit-app.pdf` (a fixed test Secret Key, `VEYQ_WRITE_FIXTURES=1`) into the web's
  `src/web/src/features/account/emergencyKit/__fixtures__/`; the web's kit import reads it by each of its three paths
  (token in the bytes, `Keywords`, the drawn page's QR) and the core's `KitFixtureTest` reads it through PDFBox's drawn
  page; a core test re-writes it and compares byte for byte, so a drift in the writer is red until the fixture is
  re-recorded on purpose.
- **Never shown.** The web never puts the Secret Key on screen (`EmergencyKitSave.vue`); neither do the apps. The step
  is the web's: *Save your Emergency Kit* → the platform's save dialog → "I saved it" enabled only after the core
  reported the file written → only then the flow's danger button. #863's "an Emergency Kit shown in the app" is owner
  question 1.
- **Where the bytes go — the one plaintext secret the apps write, and only where asked:** the core writes straight into
  the dialog's target: Android `CreateDocument` (the content `Uri`'s stream), macOS `NSSavePanel`, **iOS
  `fileExporter` with an in-memory `FileDocument`** (not the `downloadDir` + `fileMover` path of R13, which keeps a copy
  in the container first), Linux `gtk::FileDialog::save_future` (in the Flatpak the portal's path), Windows
  `FileSavePicker`; the bridge carries the path, never the bytes (ADR 0137). No share sheet, no print. A failed or
  cancelled save leaves no file the app made and the step not done.
- **Held by:** each platform's no-copy check of § Nothing on disk, extended — after a kit save, the app's data,
  cache and temp directories hold no file containing the Secret Key's canary text or the token (bitten: a debug path
  that also writes the kit into the cache dir → red).
- Rejected: **the platform drawing the PDF** (`PdfDocument`, `UIGraphicsPDFRenderer`, cairo, WinRT print) — four
  layouts of one document and four places the Secret Key's text is handled. Rejected: **a print action** — the web has
  none; the saved PDF prints from any viewer (owner question 2).

### D6 — Change password and change the address: the core's ports, the cold proof first

- `ColdCredential` (core) ports `proveColdCredential`/`checkColdCredential` over the core's existing unlock
  (`kekPasswordSecret`, `decodeKdfParams`, the PASSWORD wrap); the refusals the web's (rejected, no floor, locked,
  stale). The Secret Key is typed, pasted, read from a kit file or scanned through the **sign-in's existing Secret Key
  control** (`KitImport`, the kit scanner), never kept after the call.
- `PasswordChange` ports `changePassword.ts` step for step (R6), the proof statement `passwordChangeProofStatement`.
  After a 204: the vault keeps the same Master Key; **this device's saved key is gone on the server** (R6), so the core
  forgets its local wrap as *Stop trusting* does and the trust row offers *Trust with password* (the existing
  `trustWithPassword`) — the web's "remember this device" offer. The offline store survives (same Master Key).
  The new password passes the web's length policy and the breached-password check the apps' sign-in path already
  makes, if it makes one (measure in 3f; if the apps have none, the length policy alone and a row).
- `AddressChange` ports `emailChange.ts`'s request. **The confirm is the web's alone** (owner 2026-11-18, question 3): the
  mail keeps its ordinary web link and the web's page (`ConfirmEmailChangeView`) confirms as today; there is no app-side
  confirm step. The app takes the new address from its next account read — where the confirm revoked its session (a
  confirm revokes every other family, R6), that is the next sign-in with the new address — and re-addresses the account it
  keeps (the remembered sign-in, the account list's address: measured in 3h), then offers *Save a new Emergency Kit*
  (password + Secret Key → `checkColdCredential` → D5 with the new address and the same Secret Key). A web banner *Open
  in the app* carrying a token-free deep link that only opens the app is block H's (#435), not this CR's.
  Rejected: **pasting the mailed link into the app** — too cumbersome (owner); **a custom-scheme link in the mail** — often
  not clickable in mail clients, and its token can be intercepted by another app claiming the scheme; universal/app links
  are impossible for a self-hosted domain.

### D7 — Replace the keys: the core ports the whole rotation, and finishes rotations at every unlock

- `KeyRotation` (core, `account/rotation/`) ports `rotateKeys.ts` (the cold gate, the new identity, Master Key and
  Secret Key, the key-chain step, the staged self-grants in chunks of 256, rooted invites and join endorsements, the
  re-sealed pins and register, the doubly signed proof, up to 5 publishes, `RotationOutcomeUnknown` locking the vault,
  `resumeCommitted`), `afterCommit` (trust marks carried to the new slot, node pins re-sealed, and each application's
  Master-Key material through a new `AppKinds.masterKeyRotated` hook — Memories' item marks), re-signing the account's
  verifications (D3's code) and `finishRotation` with `commitRotation`.
- **The order is the web's:** the gate (facts, password, Secret Key, "also choose a new password" preselected) → the
  cold proof → D5's kit step (the new Secret Key written, never shown) → "I saved it" → a danger confirmation → the
  publish → the per-scope status (waiting, re-keying, secured, blocked) and *Links to renew*.
- **`finishRotation` runs at every unlock and while unlocked** (the web's 45 s), so a rotation made on the web is
  finished by an app too (R7: today only a web device finishes one).
- **The store:** the Master Key changes, so ADR 0140 decision 2 holds as written — the offline store's header no longer
  opens and the store is wiped and refilled, on the rotating device as on every other; the gate's facts say so. Rejected:
  **re-wrapping `K_off` under the new key on the rotating device** — content pinned after the rotation would stay
  readable to whoever held the old Master Key and this disk, against ADR 0060's forward secrecy.
- **A moderator (D9)** is refused before the gate with *Replace your keys on the web* — the rotation stages moderation
  grants (R7), which the core cannot seal (#965).

### D8 — Erase the account: the core's port, then the device forgets the account

`AccountErasure` ports `getOwnAccountErasurePreview`, the last-owner choices (delete along or a member as owner, the
member list of that tenant read as the web reads it), the password-only attestation, `eraseOwnAccount`; then the core
does what the web's `explainEnding('erased')` does for a browser: wipes this account's offline store (ADR 0140's wipe
marked first), removes its saved key from the device store, its trust marks, its remembered sign-in, and leaves the
account list — a sign-out with nothing left behind (measured in 3j against the existing sign-out/remove paths). The
Server Owner is told so (`409 server_owner_account`, the web's words). The last moderation-key holder (D9) is refused.

### D9 — What the apps cannot do yet is said, not half-done

The core holds no moderation key (R9). Where the rotation plan or the erasure preview names one (the preview's
`serverOwnerDelivery`) or the account holds a sealed grant of it (`listMyModerationGrants`, what the web's `ModerationKeyStage` re-seals), the app shows the web's facts and *Do this on the web* with the
server's address, and sends nothing. #965 files the moderation key in the apps. Rejected: **a partial rotation without
the moderation grants** — the server refuses it, and rightly.

### D10 — Where it lives, per platform (ADR 0123 decision 9)

The Settings page gains, in the web's order, *Your profile*, *Password*, *Email address* (beside the address the
Account section shows), *Secure your account* (replace the keys) and *Erase account*, each a section that opens its
flow; `SettingsSection` gains each (the search finds them). Flows of more than one step are each platform's own
multi-step surface:

| Platform | A flow | The danger step | Secret entry |
|---|---|---|---|
| Android | a full-screen `Dialog` with M3 `TopAppBar` steps, `SecureFlagPolicy.SecureOn` wherever the password or the Secret Key is typed | `AlertDialog` with an error-coloured confirm | `OutlinedTextField` with `PasswordVisualTransformation`; the sign-in's kit control |
| iOS, iPadOS | a `.sheet` with a `NavigationStack` of steps, `.secretOnScreen()` | `.confirmationDialog` with a destructive role | `SecureField`; the sign-in's kit control |
| macOS | a sheet, `.secretOnScreen()` | `.alert` with a destructive button | `SecureField` |
| Linux | an `AdwDialog` with an `AdwNavigationView` of pages | `AdwAlertDialog` with `destructive` response | `AdwPasswordEntryRow` |
| Windows | a `ContentDialog` per step or a page in the settings frame, `ScreenSecrets.Guard` (the `PasswordBox` peek) | `ContentDialog` with an accent-free primary | `PasswordBox` |

The person page: *Edit profile* on one's own (Android a `TopAppBar` action, Apple a toolbar *Edit*, Linux a header
button, Windows a `CommandBar` button); the safety number as a section below the name (own: *Show*; another's: the
state and *Compare*). Words: the web's `profile.*`, `profilePicture.*`, `safety.*`, `changePassword.*`, `emailChange.*`,
`rotation.*`, `kit.*`, `erasure.*`, `sessions.rename*` where the web has them; owner question 4 for the apps' own.

### D11 — The bridge (Linux, Windows)

Each flow is a model with an id (`Call.ProfileOpen` → `Answer.ProfileOpened(id)`, `Watched.Profile(id)` →
`Update.Profile(id, ProfileShown?)`; likewise `PersonTrust`, `PasswordChange`, `AddressChange`, `KeyRotation`,
`AccountErasure`) and plain calls per action; `Settings.RenameFamily(familyId, label)`. Typed secrets are inbound only
and each listed in `SecretFreeSchemaTest`'s `typedIn` (`PasswordChange.Run.current|next|secretKey`,
`AddressChange.Request.password|secretKey`, `KeyRotation.Prove.password|secretKey`, `KeyRotation.Run.newPassword`,
`AccountErasure.Run.password`, `Kit.Save.password|secretKey`); the kit save is `Kit.Save(model, path)` — a path, never
bytes; a picture is `Profile.Picture(path, pose)` and the host's 256 px RGBA answer through the existing
`Media.Tiers`-style host request. `closeTenant()` and sign-out drop every model.

### D12 — Test data: every pass of this CR is a throwaway's, made and erased by the pass

The seeded principal is the shared test account (Server Owner, the dev tenant's owner) and the seeded member its
partner; **no pass of this CR signs in as either**. A new dev tool, `src/web/scripts/throwaway-account.mts`
(`pnpm run throwaway:account` in `src/web/`), reusing `instanceBirth.mts`:
- **create** `--out <file>`: as the principal (read from `.run/.env`, never printed) on the admin listener, `createTenant`
  for a fresh `tw-<stamp>.localhost` with owner `tw-<stamp>@veyq.localhost`; registers that owner from the returned
  invite (the cold birth `@veyq/crypto` already does for the seeder), creates its personal scope, points the tenant's mail
  at Mailpit as the seeder does; `--with-member` adds a second throwaway through a **link** invite (`createMemberInviteLink`:
  its token answered to the owner, so no mailbox is read — *built so at phase 2c*: on this Mac the dev backend sends no
  mail, JavaMail's EHLO stalling on a hostname `/etc/hosts` lacks, so the seeder's mailed invite fails `503
  mail_send_failed`; the link form needs only sendable settings, ADR 0129 decision 3); writes `VEYQ_EMAIL`, `VEYQ_PASSWORD`, `VEYQ_SECRET_KEY`, `VEYQ_TENANT_DOMAIN` (and the
  member's as `VEYQ_USER2_*`) to `<file>` with mode 0600, **outside the repository** (the agent's scratchpad).
  **Measure first** (phase 2c): that `registerAndAcceptTenantInvite` takes `createTenant`'s owner invite for an address
  with no account, and that a new `*.localhost` tenant routes on the dev instance (`DevSecondTenantTest` did); if the
  first does not hold, the throwaway joins the dev tenant by a mailed member invite instead and the CR's passes say what
  that leaves (a removed member's tenant-scope rotation queued for the principal).
- **erase** `--erase <file>`: signs in as the throwaway and `eraseOwnAccount` (its sole-member tenant deleted along,
  ADR 0101 decision 9), then reports the pending erasure (purged after 14 days).
- **Refuses** any `<file>` whose address equals `.run/.env`'s `VEYQ_EMAIL` or `VEYQ_USER2_EMAIL` — as do the core's new
  destructive live tests, which read `VEYQ_LIVE_THROWAWAY_ENV` and **fail** (not skip) when it names either.
- An app pass: create → point `VEYQ_ENV_FILE` at the file for `devlogin.py` / `run.py --devlogin` (they read it already,
  R14) → the pass → `LiveRun.removeAll`/the app's sign-out → erase. The device-register entries a pass leaves go with
  the throwaway's account at its purge, not into the principal's register (native-apps.md § Open's orphan line).

## Inventory (locators — run, read as lists)

| What | Command |
|---|---|
| The web's flows to port (D2–D8) | `ls src/web/src/features/account/{changePassword,email,rotation,erasure,profile,emergencyKit} src/web/src/features/account/coldCredential.ts src/web/src/features/account/safetyCode.ts src/web/src/features/account/tenantTrust.ts` |
| The web's words to carry (D10) | `grep -n "^  \(profile\|profilePicture\|safety\|trust\|changePassword\|emailChange\|rotation\|kit\|erasure\|sessions\): {" src/web/src/i18n/index.ts` |
| The vector sections the core does not read (D4) | `/usr/bin/python3 -c "import json,re;d=json.load(open('contracts/crypto/vectors/v1.json'));s=open('src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/Conformance.kt').read();print([k for k in d if '\"'+k+'\"' not in s])"` |
| The Settings sections to extend (D10) | `sed -n 15,24p src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/search/SettingsSection.kt`; `grep -n 'header("' src/mobile/android/src/main/kotlin/me/raddatz/veyq/mobile/android/SettingsScreen.kt`; `grep -n "Section" src/mobile/apple/Veyq/SettingsView.swift`; `grep -n "fn draw_groups" -A20 src/mobile/linux/src/account_settings.rs`; `grep -n "Header" src/mobile/windows/Veyq/AccountSettingsPage.cs` |
| The no-copy checks to extend (D5) | `grep -n "no-copy\|no copy" src/mobile/CLAUDE.md` |
| Each platform's Secret Key control to reuse (D6) | `grep -rln "signin_kit_" src/mobile/android/src src/mobile/apple/Veyq src/mobile/linux/src src/mobile/windows/Veyq` |
| The site pages that tell these features (phase 9) | `ls site/src/content/docs/account/ site/src/content/docs/de/account/` |
| Docs naming these as the web's only (phase 9) | `git grep -n "#863\|#949\|only the web shows it\|own profile and the safety numbers" -- docs src/mobile/CLAUDE.md site` |

## Gates at refine

Every `/usr/bin/python3 scripts/validate_*.py` but `validate_guard_gaps.py`: exit 0 with this CR and the inbox edit in
place (2026-11-18). Not run at refine: the mobile builds and tests, `pnpm run check` — phases 2 and 3 record theirs
before their first edit.

## Phases

Each phase ends with every platform building. The core phase is ordered by soundness: what touches no key first, the
key-touching flows after the vectors that hold them, erasure last.

- [x] **1 the ADR and the docs it changes** — ADR 0148 (0147 at refine; 0147 is cr-inventory-space-items' on another
  branch, `git log --all --oneline -- 'docs/decisions/0147*'`, so the next free number across all branches) with D1–D12
  and the Alternatives; ADR 0060, 0074, 0084, 0101's headers name the apps as planned; the use cases
  (`uc.account.change-password`, `.change-email`, `.rotate-keys`, `.erase-account`, `.sessions`,
  `uc.spaces.edit-profile`, `.set-profile-picture`, `.enforce-verified-identities`) name the apps as planned;
  `native-apps.md` § Open's register line and #863's line point at this CR; ADR 0084 decision 3's and
  `uc.spaces.set-profile-picture`'s "field key derived from `K_tenant`" checked against ADR 0115 and corrected if
  stale (the field key derives from K_v, whose wrap K_tenant holds). · Done when: every guard but guard_gaps exits 0.

- [x] **2 contracts, the web's tests of them, the throwaway tool** — (a) `auth_attestation.change_password` and
  `safety_code` in `v1.json` (D4), generated by the independent Python script beside the others; (b)
  `src/packages/crypto`'s conformance test and the web's `safetyCode.test.ts` read them; (c) `throwaway-account.mts`
  (D12) after its two measurements, run once create → erase. *Measured 2026-11-18:* `registerAndAcceptTenantInvite`
  takes `createTenant`'s owner invite for an address with no account (with the TENANT birth grant), and a fresh
  `tw-<stamp>.localhost` routes (its personal scope created through it); the erase lists the pending erasure and leaves
  the tenant `PENDING_DELETION`. The vectors' generator is a scratch script, as its neighbours' were (not committed). · Done when: `pnpm run check` in `src/packages/crypto` and
  `src/web` green; a throwaway made and erased (its pending erasure listed); **bites** (snapshot and restore): (a) one
  byte of the new attestation's signature flipped → the packages' test red; (b) the `veyqid:1` case's verdict changed to
  match → `safetyCode.test.ts` red; (c) `--out` pointed at a file holding the principal's address → refused, exit ≠ 0.

- [ ] **3 the core** — `./gradlew :core:jvmTest :core:macosArm64Test` green after each step, the live tests run as a
  throwaway:
  (a) **vectors**: D4's sections in `Conformance.kt` (JVM and libsodium);
  (b) **register rename** (D1);
  (c) **safety numbers** (D3): digits, chain code, `veyqid`, `PersonTrust`, `verify`; live: two throwaways of one tenant
  verify each other and each reads the other's row;
  (d) **profile** (D2) without the picture: `ProfileModel`, the stale-epoch retry; live: a name set, read back by the
  other throwaway's `People`;
  (e) **picture**: the encoder measurement, `WebpEncoder` per target, the budget stepping and the padding; live: set,
  read back by `MemberAvatars`, cleared;
  *3a–3e built 2026-11-18* (the next phases build on them): `crypto/AccountProofs` (the proof key, the four statements,
  the attestations, the stage digest), `crypto/Verifications`, `trust/SafetyNumbers` (digits, chain code, `veyqid:2`),
  `JoinRecords.sealValueField` (a value-key field written), `session/PersonTrust` (in `session/`, beside the anchoring it
  reads, not `trust/`) with `VerificationCount`, `session/ProfileModel` with `TenantProfileDoor`, `media/WebpEncoder`
  (`LibwebpEncoder` on every native target, `AndroidWebpEncoder` in the Android host), `VeyqAccount.reopenScope` (a scope
  opened anew after a `409 stale_epoch`); live as a throwaway: `LiveAccountFlowsTest` (`VEYQ_LIVE_THROWAWAY_ENV`,
  `Throwaway`). 3e's measurement: libwebp's encoder builds for all six native slices (ADR 0148 § Consequences).
  (f) **cold proof and password** (D6); live: the throwaway's password changed, the old one refused at sign-in, the new
  one signs in, the Master Key the same (a node of its personal scope still opens);
  (g) **kit writer** (D5) with `kit-app.pdf` and its three readers;
  (h) **address change** (D6); live: request, the link read from Mailpit, confirmed as the web's page confirms it (the
  test calls `confirmEmailChange` with the link's token in a web-like session), the old address refused at sign-in, the
  new one signs in, the re-addressed remembered sign-in — *machine hint (2026-11-18): this Mac's dev backend sends no
  mail (JavaMail's EHLO stalls on a hostname `/etc/hosts` lacks, the owner's fix), so the mailed link may not arrive*;
  (i) **rotation** (D7) with `finishRotation` at unlock, `AppKinds.masterKeyRotated`, the store's wipe; live: the
  throwaway with a personal-scope node, a Memory with an item mark and a verification of its member rotates, signs in
  with the new kit's key, the node opens, every scope reaches *secured*, the old Secret Key refused; a rotation begun
  on the web (the web's `rotateKeys` driven from a script against the same throwaway) is finished by the core's
  unlock;
  (j) **erasure** (D8, D9); live: the preview, the erasure, sign-in refused, the device's records gone.
  *3f–3j built 2026-11-19:* `session/ColdCredential` (the floor proof, STALE told apart), `PasswordChange`,
  `AddressChange` (sent through the tenant's origin — `404 tenant_not_found` at the account's), `AccountErasure`, all over
  one `CredentialDoor` (`AccountCredentialDoor`, `VeyqAccount.passwordChange()`/`addressChange()`/`erasure()`); after a
  password change the saved key's record is forgotten and the store kept (`forgetSavedKeyKeepStore`), a sign-in with a new
  address re-addresses the record (`VeyqAccount.readdress`, in `finish`), an erased account is forgotten without a request
  (`forgetErased`); V1 zeroes the Argon2id input copies it builds. 3g: `account/KitWriter` (standard 14 fonts, no
  logo image — the wordmark moves left —, no date in the Info dictionary, the visible date a parameter), `KitWords` over a
  generated `CoreText` (`"core_text": true` keys), `EmergencyKit.write` held to `emergency_kit_token` in the writer's
  direction, `kit-app.pdf` read by the web's three paths and PDFBox, re-written byte for byte; `account/KitStep` (the
  save into the path, "I saved it" only after a write). 3i: `session/rotation/AccountRotation` (`rotateKeys.ts` ported:
  staged self-grants, re-signed invites, endorsed join records, pins and register re-sealed inside the publish, the
  outcome read back, `resumeCommitted`), `VeyqAccount.swapKeys` (the keys replaced in place, the store wiped and opened
  anew), `KeyRotation` (the web's order as one model; a moderator sent to the web), `finishRotation`/`ScopeRotation`
  (commitRotation and the scope-level re-key, at every unlock and every 45 s, `RotationFinisher`), Memories' item marks
  through `AppKinds.masterKeyRotated` and `scopeRekeyParticipants`. Live as throwaways: the password changed, the old one
  refused, the same spaces open, changed back; the address change's request accepted to the mail step, then `503`
  (this Mac sends no mail) — the confirm, the old address refused and the re-address are **pending a mailing backend**
  (`LiveCredentialFlowsTest` skips there, saying so; `ReaddressTest` holds the re-address); the rotation: both scopes
  `secured`, the old Secret Key refused, the new kit signs in to the same spaces, the owner's verification still counts;
  the member erased, refused at sign-in, forgotten by the device. Not run: a rotation begun on the web finished by the
  core's unlock (the finish's unlock is held by `FinishRotationTest`). Bites seen red then green: (f) the PasswordChange's
  Secret Key not zeroed → `CredentialFlowsTest`'s recording case; (g) a `/CreationDate` → the byte-for-byte case; (h) the
  re-address skipped → `ReaddressTest`; (i) the kit gate skipped → `KeyRotationTest`; (j) the store kept across
  `swapKeys` → `OfflineWipeWiredTest`; the rebuild bound and the unlock's finish → `FinishRotationTest`; (a), (b), (k)
  were phases 2–3e's.
  · Done when: green on the JVM and `macosArm64` and every live case green as a throwaway; **bites**, each by snapshot
  and restore, each red at its own assertion: (a) the `change_password` proof statement's field order swapped → the
  `secret_key_proof` vector red; (b) `chainCodeDigits` taking 3 groups → the `chain_code` vector red; (c) the rename
  writing `door.deviceId` instead of the family's device → its unit case red; (d) the stale-epoch retry removed → the
  played `409 stale_epoch` case red; (e) the padding to 16 383 → the seal-length case red; (f) the Secret Key not
  zeroed after `PasswordChange` → the recording-suite test red; (g) a creation date written into the kit → the
  byte-for-byte fixture test red; (h) the re-address skipped after the new address's sign-in → its case red; (i) the kit
  step's "saved" gate skipped → the model's publish-before-saved case red; (j) the store kept across a rotation → its
  case red; (k) the throwaway env file named the principal → the live tests fail, not skip.

- [x] **4 the bridge** — D11's models, calls, `Watched`/`Update`, `typedIn` entries, `Kit.Save` by path; `closeTenant()`
  and sign-out drop each model; `WireTest` samples, `DispatchTest` cases per model, `LiveBridgeTest` the profile and
  the password change as a throwaway. · Done when: `./gradlew :bridge:jvmTest` green; **bites**: (a) a `secretKey` field
  added to a `*Shown` → `SecretFreeSchemaTest` red; (b) `Kit.Save` given a `bytes` field → `SecretFreeSchemaTest` red
  (no byte array); (c) the rotation model left out of sign-out → a watch delivering after it, red.
  *Built 2026-11-19:* `Settings.RenameFamily`, `Profile.*` (the picture a path and a pose; the host renders it square,
  `HostRequest.PictureSquare`), `Trust.*` (`ScanKind.SAFETY`: a `veyqid:` code held in the core, found as its scheme),
  `PasswordChange.*`, `AddressChange.*`, `Kit.*` (`Kit.Save(kit, path, language, createdEpochDay)`), `KeyRotation.*`,
  `Erasure.*` (an erased account signed out here), each watched; `closeTenant` (and so a sign-out) drops every flow.
  Deviation from D11: the Secret Key arrives typed only (#970). Bites red then green: (a) `KitStep.State.secretKey`,
  (b) `Kit.Save.pdf: ByteArray` → `SecretFreeSchemaTest`; (c) the rotations left in `closeTenant` →
  `AccountFlowsDispatchTest`. Live: `LiveAccountBridgeTest` (the profile and the password change, as a throwaway).

- [x] **5 Android** — D10's row; the words (`scripts/validate_mobile_words.py`). Two halves, each with its pass:
  **5a** rename, profile, picture, safety numbers; **5b** password, address, kit, rotation, erasure. · Done when:
  `./gradlew :android:assembleDebug` and the words guard green; the emulator passes (agent, as a throwaway, § Passes);
  the no-copy check after a kit save (`run-as me.raddatz.veyq find . -type f` and a grep of every file for the Secret
  Key's canary) empty; the Recents snapshot during the password and Secret Key steps blank.
  *Built 2026-11-19:* `AccountFlows.kt` (the flows' full-screen dialog, the profile with its square crop rendered to
  256 × 256 RGBA by `AppModel.avatarPixels`), `AccountSecretFlows.kt` (password, address, kit, rotation, erasure, each
  `SecureFlagPolicy.SecureOn`; the kit written by `KitStep.saveInto` into `CreateDocument`'s stream, the document deleted
  when the write fails), `SafetyNumber.kt` (own: digits, chain code, the core's QR; another's: state, *Compare* by scan
  or digits), Settings' sections and every named session's *Rename*, *Edit profile* in the person page's bar. A device
  a password change left without a saved unlock is offered the password trust and each level in Settings (it offered
  nothing). Pass on a throwaway API 36 emulator, as a throwaway with a member (made and erased, both pending erasures
  listed): the session renamed and read back; the name and a picture saved and read back after an unlock (the person
  page, *Change picture* from the server's revision); own safety number shown; the member compared by digits →
  *Verified by you.* read back; the address change to the `503` mail step (this Mac sends no mail — confirm pending a
  mailing backend); a kit saved into Downloads through the system's dialog; the password changed, the old one refused,
  the new one signs in, the fingerprint re-trust (`adb emu finger touch 1`) DONE; the rotation with a new password, both
  scopes *New lock*, the old Secret Key refused, the rotation's kit read by the sign-in's *Open Emergency Kit* and
  signing in; the erasure refused without the last owner's choice, then erased, the device's offline store and record
  gone, the sign-in told once. No-copy: the app's files after each kit save hold none of the canaries (the Secret Key's
  text, folded, `veyqkit:`); bitten by grepping Downloads, which held the kit. Recents: the address, password, rotation
  and erasure steps' snapshots blank (10 KB) where Settings' held the page (166 KB). The package is `app.veyq` (#972);
  left: #971. Not run: a scan of a safety code (the emulator's camera shows no member's code; Android ↔ Linux is § Passes'
  own row), the rename of another device than this one (the throwaway had one session).

- [ ] **6 Apple (iOS, iPadOS, macOS)** — first #917 (the rename alert's field bound at presenting), since D1 reuses
  the alert; then D10's rows, the kit through `fileExporter` (iOS) and `NSSavePanel` (macOS). · Done when: the macOS
  and iOS-simulator SDK builds green; the macOS pass (agent, its accessibility tree); the container's no-copy check
  after a kit save on macOS; the iPhone and iPad passes are the owner's.
  *Built 2026-11-19 (passes open):* #917's fix re-read and kept (the alert now also renames another named device, D1);
  `AccountFlows.swift` — a sheet with a `NavigationStack` per flow over the core's model, `.secretOnScreen()`,
  `SecureField`s, the danger step a destructive `.confirmationDialog` (iOS) / `.alert` (macOS); `KitSaveSection` (macOS
  `NSSavePanel` → `KitStep.save` by path; iOS `fileExporter` with `KitDocument`, its bytes made only when the exporter asks,
  zeroed after, #975); *Your profile* with the square cropper rendering 256 px RGBA; the person page's toolbar *Edit
  profile* and safety-number section (own: digits, chain code, QR drawn from the core's matrix; another's: state, *Compare*
  by scan or digits). Settings gains the five sections, `SettingsSection` the five ids (shared with the other platforms);
  `appleMain`'s `AppleAccount` carries what Swift cannot (default arguments, a typed key, bytes in one copy). The macOS
  and iOS-simulator SDK builds green. **Not run:** the macOS pass and the no-copy check — the screen was locked all
  phase (#976); *Links to renew* has no core state yet (#977).

- [ ] **7 Linux** — first #957 (hold the `ScrolledWindow` itself), since D10 grows that page; then D10's row over the
  bridge. · Done when: `run.py test`, `bridge`, `build` green; the Broadway pass (agent); the no-copy check of the
  app's data and cache directories after a kit save.

- [ ] **8 Windows** — D10's row over the bridge; `ScreenSecrets.Guard` on every step with a `PasswordBox`. · Done
  when: `run.py test`, `bridge`, `build` green on the VM; the UIA pass (agent); the no-copy check of the package's
  `LocalState`, `LocalCache` and `TempState` after a kit save.

- [ ] **9 harvest** — the site's `account/` pages (EN+DE: `your-profile`, `verify-a-person`, `devices-and-sessions`,
  `change-password`, `change-email`, `replace-your-keys`, `erase-your-account`) tell the apps (where each is, the kit
  saved through the system's dialog, the address confirmed on the web); ADR 0148 built with its passes; the use cases and their
  coverage files gain the apps' tests; `native-apps.md` § Built and § Open; `src/mobile/CLAUDE.md` (the new models, the
  throwaway rule beside § live tests, the kit in § Nothing on disk); #863, #949 deleted as done; the lessons to
  `changes/README.md` or the lines they bite; this CR deleted. · Done when: `pnpm run check` in `site/` green and every
  guard but guard_gaps green.

## Passes

Every pass signs in as a throwaway (D12), made before and erased after.

| Pass | Who | What it proves |
|---|---|---|
| Android emulator (`veyq_pixel`) | agent | 5a/5b's flows end to end, the kit saved into Downloads through `CreateDocument` and read back by the sign-in's kit import, the re-trust after the password change with the emulator's fingerprint (`adb -e emu finger touch 1`, remembered, not run at refine), TalkBack's labels on the new sections |
| macOS build | agent | the same flows, the kit through `NSSavePanel`, the safety code scanned through the test camera |
| Linux Broadway | agent | the same flows over the bridge, the kit through the portal's dialog |
| Windows VM | agent | the same flows over the bridge, the kit through `FileSavePicker`, Hello's re-enrolment where the VM offers it (else DPAPI's) |
| Two apps compare safety numbers | agent | Android ↔ Linux (the two throwaways of one tenant): one scans the other's code through its test camera, both read the verification |
| A kit saved by an app opens the web | agent | the app's saved kit read by the web's sign-in (`?devlogin` is the principal's, so the web's kit import is driven by a test, not a pass) — covered by phase 3g's fixture, named here so no one runs it on the principal |
| iPhone and iPad on a device | **owner** | the sheets, `fileExporter` into Files, Face ID's re-enrolment after the password change (no simulator boots, #928) |
| Touch ID and Windows Hello for real | **owner** | the re-trust after a password change and after a rotation on real hardware |
| A real mailbox | **owner** | an address change to a real address, the link opened on a phone (Mailpit covers the agent's) |
| A real camera | **owner** | a safety code scanned between two phones (#912) |

## Owner questions

1. **The kit shown on screen?** #863 says "an Emergency Kit *shown* in the app"; the web never shows the Secret Key and
   only saves the PDF (R8). *Recommendation:* as the web — saved, never shown: a screen is photographed, recorded and
   shoulder-surfed, a file the person chose is not, and one rule on every client is simpler to explain.
2. **A print button?** *Recommendation:* no, as the web — the saved PDF prints from the system's viewer; a print path
   is a fifth place the Secret Key's text is handled (a spooler on Linux and Windows keeps files).
3. ~~Confirm an address change by pasting the link in the app?~~ **Answered (owner 2026-11-18): no** — the web's page
   confirms; the app takes the new address at its next account read (D6). An *Open in the app* banner is block H's (#435).
4. **Copy for the apps' own words** (EN / DE; everything else is the web's): `account_on_the_web`: "Do this on the web: {server}" /
   "Erledige das im Web: {server}" (D9); `kit_save_title`: "Save your Emergency Kit" / "Speichere dein Notfall-Kit" (if
   the web's `kit.*` has no title of that shape); `rotation_store_wiped`: "What this device keeps offline is fetched
   again." / "Was dieses Gerät offline hält, wird neu geladen." *Recommendation:* these.
5. **A moderator replaces their keys or erases their account only on the web for now** (D9, #965). *Recommendation:*
   yes — few accounts are moderators, and the web does it fully.

## Inbox rows

#863 and #949 (this CR, refining), #965 (new: the moderation key in the apps), #966 (new: the task queue's jobs in the
apps), #967 (new: *Who's who* in the apps); #435 (block H: links opened in the app), #917 and #957 (fixed first in
phases 6 and 7), #928, #912.

## Definition of done

Phases 1–9 checked with their Done-when met; the new vector parts read by the web and by the core on the JVM and
libsodium; the bites above each seen red and green again; every live test and pass run as a throwaway, each throwaway
erased (its pending erasure listed in the console); the owner's passes named as open where not yet run; the Use pages
in EN and DE updated with `pnpm run check` in `site/` green; every `scripts/validate_*.py` but guard_gaps green.
`git diff --stat` over `src/backend/` is empty; over `src/web/` it names the safety-code test, the kit fixture and its
test, and `scripts/throwaway-account.mts` (with its `package.json` line) only.

## Retrospective

_(filled at the end of the implementation)_
