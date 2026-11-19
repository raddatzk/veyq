---
id: cr-open-in-the-app
title: "Open in the app — a veyq: link that carries nothing, registered on every app, and the web's banner on the pages a mailed or sent link opens"
status: planned
satisfies: []
adrs: ["0148", "0120", "0123", "0139"]
invariants:
  - "operator-blind E2EE: a veyq: link carries no key, no token, no id and no address; the app reads nothing of it but which of two screens to show"
  - "self-hostable by default: no universal link and no app link — they need a domain the app's publisher serves, which a self-hosted instance is not"
  - "the apps behave exactly like the web; only the look is the platform's (owner 2026-11-19)"
  - "no pass signs in as the seeded principal or member: every pass is a throwaway's (ADR 0148 decision 12)"
---

## Goal

Inbox #435's handover, V2 block H's second CR, implemented after cr-app-received-links (**done 2026-11-19**, harvested
into [ADR 0150](../docs/decisions/0150-a-received-link-opens-in-the-app-as-its-guest-the-share-pages-rules-in-the-core-a-kept-link-a-bookmark-the-app-opened-by-a-veyq-link-that-carries-nothing.md):
its *Open a link* screen is this CR's target). **Re-read at its landing:** *Open a link* sits on the sign-in and the
unlock screens alone (owner 2026-11-19, ADR 0150 decision 4) — what `veyq://link` opens while a tenant is open (no field
there) is this CR's to decide before phase 3. The owner decided (2026-11-18,
ADR 0148 decision 6) that a web page a mailed link opens shows an *Open in the app* banner carrying a token-free
custom-scheme link that only opens the app, with no universal or app links. After this CR every app answers `veyq:`
links — Android, iOS, macOS, Linux (the Flatpak), Windows (the MSIX) — and the web shows the banner on the share page
(where it copies the link and opens the app's *Open a link*) and on the pages a mailed link opens (where it opens the
app once the web has done the step).

## Context & constraints

- **Read first:** ADR 0148 decision 6 and its Consequences; ADR 0150 (its § O is this CR's; its Consequences say what the guest
  view's passes proved); ADR 0139 (packaging: the MSIX, the Flatpak); `src/mobile/CLAUDE.md` § Prove your work.
- **Where what runs** (from `src/mobile/`, `src/mobile/CLAUDE.md` § Prove your work): `./gradlew :core:jvmTest
  :core:macosArm64Test :bridge:jvmTest`; `ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :android:assembleDebug`; the
  Apple builds after `./gradlew :core:assembleVeyqCoreDebugXCFramework`; `python3 linux/run.py test|build`;
  `python3 windows/run.py build` over ssh to the VM; live cases as a throwaway (`VEYQ_LIVE`, `VEYQ_LIVE_THROWAWAY_ENV`;
  a Windows throwaway with `--tenant tw-win`, the VM mapping `tw-win.localhost` only). The Windows protocol exists only in the packaged app
  (`run.py package`, R5); its install on the VM is measured first in phase 6. **No iOS simulator boots (#928):** the
  iPhone pass is the owner's.
- **Out of scope:** a `veyq:` link that carries a link, a key or a token (O1 refuses it); app or universal links;
  detecting whether the app is installed (no web API does it without a probe the browser shows as an error).

## Measured at refine (2026-11-19)

Branch `claude/h-refine` at `9cc562ae2`. `M=src/mobile`, `W=src/web/src`.

| # | What | Where / command |
|---|---|---|
| R1 | **Nothing in the repo opens an app or claims a scheme:** no `veyq://`, no `apple-app-site-association`, no `assetlinks`, no banner; "deep link" means an SPA route in the comments that say it. The QR texts `veyqpair:`, `veyqsignin:`, `veyqkit:`, `veyqid:` are payload prefixes read by a scanner, no OS scheme. | `git grep -n -i -E 'veyq://\|apple-app-site-association\|assetlinks\|open in the app' -- src/web src/backend infra site src/mobile` → 5 hits, each a comment on "the Pot open in the app", none a link; `grep -n 'veyq' $M/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/qr/QrSchemes.kt` |
| R2 | **Android:** `MainActivity` (`singleTask`) has MAIN/LAUNCHER, SEND and SEND_MULTIPLE filters, no VIEW, no `<data android:scheme>`; `onNewIntent` → `devLogin`, `receive`. | `grep -n 'android.intent.action\|<data\|launchMode' $M/android/src/main/AndroidManifest.xml`; `grep -n 'onNewIntent' -A5 $M/android/src/main/kotlin/me/raddatz/veyq/mobile/android/MainActivity.kt` |
| R3 | **Apple:** no `CFBundleURLTypes` in `Veyq/Info.plist`, no `onOpenURL`, no associated domains. | `grep -c CFBundleURLTypes $M/apple/Veyq/Info.plist` → 0; `git grep -n 'onOpenURL\|associated-domains' -- $M/apple` → none |
| R4 | **Linux:** the `.desktop` file is written by `theme/generate_theme.py` with `Exec=veyq`, no `MimeType`, no `%u`; `main.rs` builds the `adw::Application` with `connect_activate` only (no `HANDLES_OPEN`, no `connect_open`); the Flatpak installs the generated file. | `grep -n '"Exec=veyq"' $M/theme/generate_theme.py`; `grep -n 'connect_activate\|HANDLES_OPEN\|connect_open' $M/linux/src/main.rs`; `grep -n '\.desktop' $M/linux/flatpak/manifest.yml` |
| R5 | **Windows:** `Package.appxmanifest.in` has one `<Application>` and no `<Extensions>`; the development loop is unpackaged (`WindowsPackageType None` unless `VeyqPackage`), so only `run.py package`'s MSIX can carry a protocol; `App.OnLaunched` ignores its arguments. | `grep -c 'Extensions\|uap:Protocol' $M/windows/Veyq/Package.appxmanifest.in` → 0; `grep -n 'WindowsPackageType' $M/windows/Veyq/Veyq.csproj`; `grep -n 'OnLaunched' -A6 $M/windows/Veyq/App.xaml.cs` |
| R6 | **The pages a mailed link opens:** `/tenant/accept#<token>` (`SmtpTenantInviteMailer`), `/recover#<token>` (`RecoveryMailer`), `/confirm-email#<token>` (`EmailConfirmationMailer`), `/confirm-email-change#<token>` (`EmailChangeMailer`); a share link `/s/<id>#k=` is sent by a member, not mailed by the server. | `grep -n "path: '/tenant/accept'\|path: '/recover'\|path: '/confirm-email" $W/router.ts`; `git grep -n 'accept#\|recover#\|confirm-email' -- src/backend/core/src/main` |
| R7 | **The web already puts a share link with its key on the clipboard**, on the person's click (the share dialog's copy). | `grep -n 'clipboard.writeText' $W/core/sharing/FolderShareDialog.vue` |

## Decisions (ADR 0150 § O)

### O1 — `veyq:` with two forms, and nothing read

The scheme is **`veyq`** — the product's name, distinct from the QR prefixes (R1). Two forms: **`veyq://open`** opens the
app where it is; **`veyq://link`** opens *Open a link* (ADR 0150 decision 4). The core's `DeepLink.parse(uri)`
answers `Open` or `Link` and **reads nothing else**: any other host is `Open`; a path, a query, a fragment, user info are
ignored, never stored, never logged. So a crafted `veyq://link#k=…` or `veyq://link?u=https://…` does what `veyq://link`
does.
- **Why the link carries no key** (#438's "not unguarded"): a custom scheme is owned by no one — any app on the device can
  declare `veyq:` and receive what a page sends there (which one wins is the system's choice on iOS, the person's in a
  chooser on Android). A key in it would be handed to whoever claimed the scheme; it would also reach the browser's
  history and, on the desktops, the handler's command line (`%u`, readable in the process list). With nothing in it, a
  hijacker learns that a veyq page asked for the app.
- Rejected: **the link with its key in the deep link's fragment** (one tap instead of a paste) — the reasons above.
  Rejected: **the key sealed for the app** — the browser shares no key with the app it would seal to. Rejected:
  **universal / app links** — a self-hosted domain has no publisher's association file (owner 2026-11-18).

### O2 — The web's banner

A slim banner, one per page, dismissible; the dismissal kept per device (`localStorage`, wrapped in try/catch — a
convenience, not state). *Owner question 1* names the pages and words; the recommendation:
- **The share page** (`/s/`, READ, READ_WRITE and WRITE_ONLY; not `#moderation`): **Copy link and open the app** — it
  writes the page's link, normalized as a bookmark's (no `&report`), to the clipboard on that click, as the share
  dialog's copy does (R7), then navigates to `veyq://link`; the app's *Open a link* has the paste control at hand.
  The password is not copied; the person enters it in the app as on the page.
- **The four mailed pages** (R6): the banner appears **after** the page's own step succeeded (the address confirmed,
  the invite accepted, the recovery done) and opens `veyq://open`; the app re-reads the account at its next sign-in
  (ADR 0148 decision 6). Before the step, nothing — the step is the web's.
- On every browser (the desktops have apps too); a browser without a handler shows its own "cannot open" — the banner's
  line says it is for the installed app.

### O3 — The registrations

| App | What | Where it arrives |
|---|---|---|
| Android | an intent filter on `MainActivity`: VIEW, DEFAULT, BROWSABLE, `scheme="veyq"` | `onCreate` / `onNewIntent` (`singleTask`) → `DeepLink.parse` |
| iOS, macOS | `CFBundleURLTypes` with `veyq` in `Veyq/Info.plist` | `.onOpenURL` on the app's scene |
| Linux | the `.desktop` gains `MimeType=x-scheme-handler/veyq;` and `Exec=veyq %u`; the application takes `HANDLES_OPEN` and `connect_open` (a second launch hands the URI to the running instance over D-Bus and exits) | `connect_open` |
| Windows | `<uap:Extension Category="windows.protocol"><uap:Protocol Name="veyq"/>` in the MSIX's manifest; `AppInstance.FindOrRegisterForKey` redirects a second activation to the running instance | `AppInstance.GetActivatedEventArgs()` → `ProtocolActivatedEventArgs` |

The unpackaged Windows loop and a Linux run outside the Flatpak register nothing; their passes use the packages.

### O4 — What the server sees, and the residual

Nothing: a `veyq:` navigation leaves the browser for the system, not for a server; the banner's dismissal is device-local.
**Residual** (into `site/…/security/known-limitations.md` and ADR 0150's Consequences): on the share page, the click
puts the link — its key — on the clipboard and then opens whichever app holds `veyq:`; an app that claimed the scheme
and reads the clipboard when it is opened learns the link. It is the exposure of the share dialog's copy (R7) plus the
launch; the person chose to copy; a phone's system shows the paste (iOS asks, Android 13+ shows a notice).

## Phases

- [ ] **1 core and bridge** — `DeepLink.parse` and `Call.DeepLink(uri)` (a desktop hands the URI in; the bridge answers
  the screen). Tests: the two forms; every other input `Open`. **Bites:** (a) a parser that keeps the fragment or the
  query → the test that a `veyq://link#k=<canary>` and a `?u=<canary>` leave no canary anywhere in the answer red;
  (b) an unknown host answered `Link` → red. · Done when: `:core:jvmTest :core:macosArm64Test :bridge:jvmTest` green.
- [ ] **2 web — the banner** (O2) — `core/openInApp/` (a component and its pure rule: which page, which form, the
  normalized link); the four mailed views show it after their step; `ShareView.vue` shows it but in a review. Tests:
  the hrefs are exactly `veyq://open` / `veyq://link` and hold no `#`, `?` or key; the clipboard gets the normalized
  link without `&report`; a dismissed banner stays dismissed and a throwing storage still renders. **Bite:** the href
  built from `location.href` → the no-key test red. · Done when: `pnpm run check` in `src/web/` green and the browser
  pass (§ Passes) clicked.
- [ ] **3 Android** (O3). · Done when: `:android:assembleDebug` green; `adb shell am start -a
  android.intent.action.VIEW -d 'veyq://link#k=CANARY'` opens *Open a link* and `logcat` holds no `CANARY` from the app.
- [ ] **4 Apple** (O3). · Done when: both Apple builds green; `open 'veyq://link'` on the Mac opens *Open a link* (screen
  unlocked, else named open); iOS the owner's.
- [ ] **5 Linux** (O3). · Done when: `python3 linux/run.py test` and `flatpak` green; in a GNOME session with the bundle
  installed, `xdg-open veyq://link` opens *Open a link*, a second time in the running window.
- [ ] **6 Windows** (O3) — first measured: how the unsigned MSIX installs on the VM (developer mode's
  `Add-AppxPackage -Register` of the layout, or the package), its result into ADR 0150. · Done when: `run.py package`
  green; `start veyq://link` on the VM opens *Open a link*, a second time in the running window.
- [ ] **7 passes and harvest** — § Passes; ADR 0150's Consequences (the passes, the residual); a Use page section
  "Open in the app" in `sharing/what-guests-see.mdx` (EN+DE) and the known limitation; `native-apps.md` § Built; #435
  deleted (cr-app-received-links is done) and #438's handover clause removed; this CR deleted. · Done when: `pnpm run
  check` in `site/` and every guard but guard_gaps green.

## Passes

| Pass | Who | What it proves |
|---|---|---|
| Web in Chrome on the Mac, a throwaway | agent | the share page's banner copies the link and asks to open `veyq:`; the address-change confirm shows the banner only after it confirmed; dismissed, it stays dismissed after a reload |
| Android AVD, a throwaway | agent | a share link opened in the AVD's Chrome; *Copy link and open the app* opens *Open a link*; paste, the share as its guest; a mailed page's banner opens the app where it was |
| Linux (Flatpak) and Windows (MSIX) | agent | the same from Firefox / Edge |
| iPhone | **owner** | the same from Safari, the system's "Open in veyq?" and the paste control |

## Owner questions

1. **The banner's pages and words (O2).** *Recommendation:* the share page and the four mailed pages after their step;
   every browser; "Open in the veyq app" / "In der veyq-App öffnen", on the share page "Copy link and open the app" /
   "Link kopieren und App öffnen", the line "For the installed veyq app" / "Für die installierte veyq-App".
   **Answered (owner 2026-11-19): the banner on every page** (the app is the safer client — it is not served by the
   operator, ADR 0026), dismissable, the dismissal kept per device; on a page other than the share page and the mailed
   pages it opens `veyq://open`. **Plus Apple's Smart App Banner** (`<meta name="apple-itunes-app">`) on iOS Safari
   once the app has an App Store id (the owner's publishing step): where Safari shows it, our banner stays hidden so
   two never stack; it carries no `#k=` (an `app-argument`, if any, is token-free). Words as recommended.

## Definition of done

Phases 1–7 checked; the bites seen red and green; the passes run as throwaways and erased, the owner's named open where
not run; the Use page and the known limitation in EN (and DE for the Use page); every guard but guard_gaps green.
`git diff --stat` over `src/backend/` and `contracts/` is empty.

## Retrospective

_(filled at the end of the implementation)_
