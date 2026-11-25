# ADR 0159 — The veyq app runs on Apple TV and Android TV as a viewing form of the one app

- Status: Proposed
- Built: no
- Date: 2026-11-25
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

The owner wants veyq on the TV soon, so that every surface is kept together from now on (owner 2026-11-25): Memories'
slideshows, photos, video and music first, Depot and Expenses as far as viewing them makes sense. Sign-in is
[ADR 0059](0059-device-linking.md)'s *Sign in with a QR code*, already in the core (`session/DeviceLink.kt`,
`crypto/Pairing.kt`): the TV shows the code, a signed-in phone scans it, the phone's six digits are typed with the
remote. The new device's side needs no camera (`SignInPlan.entries` always offers *show a code*). A TV gets the whole
account, as every linked device does (owner 2026-11-25).

Three things set a TV apart from a phone: it is driven by focus and a remote, not touch; it has no keyboard, picker or
camera worth building authoring on; and it is **shared** — several people use one TV. The app already holds several
servers and accounts at once (`navigation/Connections.kt`, the drawer's switcher, ADR 0122 decision 2), but at start it
resumes every remembered account after one authentication (`SessionModel`), which is right for a phone and wrong for a
household's TV. The unlock a TV needs is [ADR 0160](0160-a-tv-unlocks-an-account-by-a-pin-the-device-hardware-binds-and-the-server-counts.md).

## Decision

1. **A TV is a form of the one app, not a second app** ([ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)
   decision 1). **Android:** the same APK, as Google recommends for an app on phone and TV — `android.software.leanback`
   and `android.hardware.touchscreen` not required, a TV activity of its own under `LEANBACK_LAUNCHER` with a banner,
   drawn with Compose for TV (`androidx.tv:tv-material`, stable). **Apple:** the same app record and bundle id
   (universal purchase), built by an XcodeGen target of its own, `VeyqTV` (tvOS 26), over the same `Veyq/Core` and the
   applications' views where they compile, with a root of its own. A target rather than a destination of `Veyq`: App
   Store Connect still says a tvOS build comes from a separate target, the share extension and `LAContext` do not exist on
   tvOS, and a root of its own keeps `#if os(tvOS)` out of the iOS root. The core gains the `tvosArm64` and
   `tvosSimulatorArm64` targets, its native libraries (libsodium, libwebp) a tvOS slice each.

2. **A TV views; it does not author.** The core's plans take the device's form, and the TV form keeps an object's
   viewing actions and removes creating, editing, moving, sharing, uploading and deleting — the same way rights remove
   functions ([ADR 0154](0154-every-app-draws-the-cores-plans-through-a-core-ui-its-applications-plug-into-as-the-webs-do.md)),
   decided once in the core, never per screen. This is not a guest view: what the TV shows is the member's view, minus
   what a remote cannot do well ([ADR 0122](0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)'s
   "share view = member view" is untouched; a TV opens no received link in this decision, inbox #1351).

3. **What each application shows on a TV:**
   - **Memories** — its list (Favorites, Recent, All, ADR 0122 decision 5), a Memory's gallery and Chapters, the
     lightbox, the slideshow with the Memory's music, video, and music playing on (in the background where the platform
     lets it). Not its map and not its journal.
   - **Depot** — Home's tree (ADR 0122 decision 3), a file opened in the same viewers when it is an image, a video or
     audio; any other file is listed and says it opens on another device.
   - **Expenses** — absent on a TV (inbox #1350 holds the other answer, a Pot's overview read-only): a Pot's entries and balances are a household's money
     on a shared screen, and nothing on it is better read there than on a phone.
   - **Search** — absent in this decision (the client-side index, [ADR 0125](0125-search-runs-in-the-client-over-what-it-can-open-across-every-space.md),
     is built per device, and a TV's storage is purgeable); inbox #1355.

4. **A TV starts at an account picker, and opens one account at a time.** Every account remembered on it is a tile
   under a name the person gives it on this TV (by default the account's own profile name; kept on this device in the
   clear, the one plaintext the TV keeps beside its records — no avatar, no content). Picking one asks its PIN
   ([ADR 0160](0160-a-tv-unlocks-an-account-by-a-pin-the-device-hardware-binds-and-the-server-counts.md)) and opens only
   that account; *Lock* and the switcher return to the picker and drop the Master Key; *Add someone* runs the QR
   sign-in. The phone's "one authentication resumes every account" does not hold on a TV.

5. **Apple TV's own users separate the app's data per person, and the PIN still guards each account.** The `VeyqTV`
   target declares `com.apple.developer.user-management` = `runs-as-current-user`: each Apple TV user gets their own
   data container, preferences and keychain for the app (verified: Apple's *Personalizing your app for each user on
   Apple TV*), so one person's accounts are not even listed under another's profile. Not the
   `…-with-user-independent-keychain` variant: nothing here should be shared across profiles. tvOS profiles switch from
   Control Center without a passcode, so a profile is no lock, and the PIN stays required. **Google TV profiles** have
   a PIN lock of their own; whether they separate an app's data is unverified (a secondary source says adult profiles
   share third-party apps), so on Android TV the picker and the PIN are the separation, and the TV phase measures it.

6. **Navigation is the platform's.** The drawer's content (switcher, Home, Favorites, Recent, the applications,
   settings) is the core's as on every app; tvOS draws it as a tab bar at the top, Android TV as Compose for TV's
   navigation drawer — the platforms' own patterns ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)
   decision 9). Focus moves by the platform's engine (SwiftUI's focus on tvOS 15+, Compose's focus on Android TV).

7. **A TV keeps no offline store and queues nothing** ([ADR 0140](0140-the-apps-keep-content-as-ciphertext-sealed-to-the-device-by-choice-or-within-a-limit-an-unlock-opens-it-offline.md),
   [ADR 0153](0153-the-apps-queue-a-persons-own-additions-made-offline-sealed-to-the-device-and-send-them-through-the-online-write-path-expenses-joins-the-store.md)
   off): tvOS keeps only about 500 KB of persistent storage beside the keychain and may purge the rest
   (Apple's archived *App Programming Guide for tvOS*), and the PIN unlock is online anyway. Media streams through the
   players the apps already use: AVPlayer with the core's range reader as its resource loader (available on tvOS),
   Android's `MediaPlayer` over `MediaDataSource`.

8. **Nothing about an account shows outside the lock.** No tvOS Top Shelf, no Android TV home-screen channel or
   *Watch Next* row in this decision: each would draw content while the account is locked. Inbox #1354 holds whether a
   content-free one is worth having.

## Consequences

- **Every application's UI gets a third form to keep** (phone, desktop, TV) — the cost the owner chose so that every
  surface moves together. The core's plans stay one; the TV draws them.
- **Older Android TVs are left out:** the app's `minSdk` is 31, and many TVs run Android 10 or 11 (remembered). Lowering
  it is not part of this decision.
- **The owner's signing steps, at the end** (owner 2026-11-20): the `VeyqTV` target's provisioning, its entitlements
  (`com.apple.developer.user-management`, the app group and keychain group the iOS target has), adding tvOS to the App
  Store Connect record, the TV banner and the Play Console's TV form factor and its review.
- **Rejected — a separate TV app** (its own bundle id or package): ADR 0122's reasons, a second sign-in and listing for
  one maintainer.
- **Rejected — tvOS as a destination of the `Veyq` target:** Xcode allows it, but the iOS root, the share extension and
  `LAContext` would each need a tvOS branch, and App Store Connect's own help still asks for a separate target.
- **Rejected — Chromecast as the way onto a TV:** the Cast sender SDK is proprietary and the receiver a Google-registered
  app (the no-vendor-locked-dependency rule). AirPlay is inbox #1330, separate.
