# src/mobile — the veyq app

One app for every application ([ADR 0122](../../docs/decisions/0122-one-app-holds-every-application-the-drawer-chooses-the-bar-belongs-to-it.md)),
a Kotlin Multiplatform base under each platform's own UI ([ADR 0123](../../docs/decisions/0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)).
A Gradle build of its own, run through its own wrapper.

- `core/` — the shared base: the v1 suite (`crypto/`), the generated API client (`build/generated/openapi`, from
  `contracts/api`, never edited), the session (`session/`), the navigation model (`navigation/`).
- `android/` — the Android app (Compose). `devlogin.py` signs a debug build on a running emulator in as the seeded dev
  principal (`.run/.env`).
- `apple/` — the Apple app (SwiftUI, iOS and macOS). `xcodegen` in that directory writes `Veyq.xcodeproj` from
  `project.yml`; the project builds `VeyqCore.xcframework` itself before it compiles. `devlogin.py <device>`
  relaunches the installed debug app in a booted simulator, signed in as the seeded dev principal.

- `theme/generate_theme.py` — the web's colour roles and brand mark for every platform (ADR 0123 decision 7): the
  builds run it (`:core:generateTheme`, `:android:generateThemeRes`, the Apple project's pre-build step); its output
  is never committed. A colour or the logo changes in `src/web/`, never here.

## Prove your work

- `./gradlew :core:jvmTest :core:macosArm64Test` — the suite against `contracts/crypto/vectors/v1.json` (with its
  bite) over BouncyCastle and over libsodium, the navigation model, and the grant check (`GrantTrustTest`: the web's
  `openOwnGrant`/`verifyOwnGrant` rules against a server the test plays). Any change under
  `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto` runs both. The first Apple build compiles libsodium
  (`buildLibsodium`, a few minutes, once). A change to a rule in `session/Grants.kt`, `session/IdentityPins.kt` or `session/Anchoring.kt` needs
  the case that refuses it: remove the rule and `GrantTrustTest` must go red.
- `VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :core:jvmTest :core:macosArm64Test --tests '*LiveSignInTest*'` —
  signs in to a running dev instance as the seeded principal on both paths and reads its spaces, objects and
  favorites (`./gradlew backend:seed` at the repository root first). With `VEYQ_LIVE_MEMBER=1` as well,
  `--tests '*MemberLiveSignInTest*'` signs in as the seeded second member (`VEYQ_USER2_*`), whose TENANT grant the
  owner signed, so it opens only through the key check; the owner starts that run, since that password may be one a
  person chose.
- `VEYQ_LIVE=http://veyq.localhost:8080 VEYQ_SEED_TENANT="verein.localhost|Turnverein" ./gradlew :core:jvmTest --tests
  '*DevSecondTenantTest*'` — dev seeding, not a check: gives the seeded principal a second tenant (the seeder births only
  one), through the app's own suite, so the switcher has two to switch between. Skipped when it already exists.
- `./gradlew :android:assembleDebug` — the Android app; on an emulator, `python3 android/devlogin.py` signs it in.
- `xcodebuild -project apple/Veyq.xcodeproj -target Veyq -sdk macosx build CODE_SIGNING_ALLOWED=NO` (or
  `-sdk iphonesimulator`) — the Apple app compiles. On a fresh checkout run `./gradlew :core:assembleVeyqCoreDebugXCFramework`
  once first: Xcode looks for the framework before the project's own pre-build step can make it.

## Traps

- The Apple primitives are libsodium 1.0.22 through cinterop (`core/sodium/`): `build_libsodium.py` builds the three
  arm64 slices from the release tarball, refused unless it matches the pinned SHA-256. GitHub's release CDN answers a
  504 now and then; the script retries, and a tarball already in `build/libsodium/` is used as it is.
- `VeyqCore` has arm64 slices only, so `apple/project.yml` sets `ARCHS: arm64`; without it a Release build (what
  `xcodebuild -target` builds) asks Kotlin's Xcode hook for x86_64 and fails with "Xcode Requested Architecture Not
  Configured in Gradle".
- The trust marks (the pin store's revision, each scope's floor and held key, `session/TrustMarks.kt`) are the device's
  own, in SharedPreferences / UserDefaults, keyed by a derivation of the Master Key; clearing the app's data is the
  web's no-mark case (a fresh device), never a lockout.
- The emulator reaches the developer's machine at 10.0.2.2; the debug build maps every `*.localhost` name there and
  keeps the name in the Host header, since the backend picks the tenant from it. Use the tenant's name
  (`veyq.localhost:8080`), never `10.0.2.2:8080`, as the server.
