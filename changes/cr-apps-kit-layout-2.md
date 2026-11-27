---
id: cr-apps-kit-layout-2
title: The apps print kit layout 2, record every kit they make, and suggest a clearer kit in Settings and once in the shell
status: in-progress
satisfies: ["uc.account.renew-emergency-kit"]
adrs: ["0157", "0148", "0151"]
invariants:
  - "the kit record is one blob sealed under the Master Key (purpose kit-record); the server never sees a layout number"
  - "a new kit is made only from a Secret Key that passed the cold proof; the record is written once the kit is kept, never at render, and no key is held across the save"
  - "a kit of layout 1 keeps opening the account (the layout is in no token)"
---

## Goal

Close the apps' gap against main's ADR 0157 (owner 2026-11-26: the apps do what the web does): the core's `KitWriter`
prints layout 2 (the key labelled "Secret Key · 9 blocks in 3 lines", the web's `kit.pdfSecretKey` verbatim); every flow
of the apps that makes a kit records it (the rotation already carried `kitRecord`; the address change and the new
renewal write it with `PUT /api/account/kit-record` once the kit is kept); Settings → Security says which kit the account
has and offers *Make a new kit*; the shell shows the one-time dismissible notice. The two `apart` word pairs that existed
only because the apps' kit was layout 1 (`kit_pdf_secret_key`, `secret_key_typo`) become twins again.

## Phases

1. Core + bridge: `KitWriter.LAYOUT = 2`; `account/KitRecord.kt` (`KitRecord.seal`/`layoutOf`, `KitStatus`);
   `session/AccountKitRecord.kt` (read/write over `AccountKitRecordApi`); `KitStep`'s `kept` hook and `State.recorded`;
   `AddressChange.kit` and `session/KitRenewal.kt` sealing the record during the proof; `AccountSettings.State.kit`;
   `ShellModel.kitNotice`/`dismissKitNotice`; the wire's `Kit.RenewalOpen|Renew|RenewalClose`, `Watched.KitRenewal`,
   `Shell.KitNoticeDismiss`, `SettingsShown.kit`/`kitNews`, `ShellShown.kitNotice`; the words; the web fixture
   `kit-app.pdf` rewritten (layout 2), the previous one kept as `kit-app-layout1.pdf`.
2. Android, Apple, Linux, Windows: Settings → Security's kit row/group, the renewal flow, the shell's notice.
3. Harvest: uc.account.renew-emergency-kit (the apps), ADR 0157 (the apps), ADR 0148 § kit, native-apps, the Use pages
   EN+DE where they speak of the kit; delete this CR.

## Decision log

- **The apps take the web's layout number, not a numbering of their own** (`KitWriter.LAYOUT = 2`): the record is shared
  by every client of the account, so "layout 2" must mean the same sheet on the web and in the apps; the apps' sheet is
  the web's renderKitPdf line for line already (ADR 0148 decision 5), only the label differed.
- **The old app kit stays as a fixture** (`kit-app-layout1.pdf`, the bytes of the earlier `kit-app.pdf`): the proof that
  a kit printed before keeps opening the account, read by token and by its drawn QR (`KitFixtureCases`, `KitFixtureTest`).
- **The record is sealed during the proof and the blob, not the key, is held until "I saved it"** (`KitStep.kept`), as
  ADR 0157 decision 4 says of the web's `writeKitRecord`; the rotation keeps carrying it in its request
  (`KitRecord.seal` under MK'), and marks the status current on commit.
- **A failed record write leaves a valid kit and says so** (`KitStep.State.recorded = false`, `Msg.KIT_RENEW_NOT_NOTED`),
  the web's E3.
- **The status is read once per unlock and shared** by Settings and the shell's notice (`VeyqAccount.kitStatus`, the web's
  `kitLayoutState`); a failed read is asked again by the next caller and shows "could not be read" in Settings only.
- **The notice's dismissal is per device and account** (the device store, `kit-notice:<account key>`, the layout it spoke
  of), the web's per browser and user; "Go to your kit" dismisses it too and opens Settings at Security.
- **Each platform's notice is its own quiet bar**: Android a surface row under the offline banner (Material has no
  two-action banner component in Compose M3), Apple a section/inset in the shell, GNOME a revealer bar under the
  `AdwBanner`s (an `AdwBanner` holds one button only), Windows a Fluent `InfoBar` (its action button and close).
- **The apps make no birth kit** (joining a tenant and setup are web-only, native-apps § Not in the apps yet), so the
  births' record is not the apps' concern.

## Retrospective

(filled at harvest)
