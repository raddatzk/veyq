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
  bite) over BouncyCastle and over libsodium, and the navigation model. Any change under
  `core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto` runs both. The first Apple build compiles libsodium
  (`buildLibsodium`, a few minutes, once).
- `VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew :core:jvmTest :core:macosArm64Test --tests '*LiveSignInTest*'` —
  signs in to a running dev instance as the seeded principal on both paths and reads its spaces, objects and
  favorites (`./gradlew backend:seed` at the repository root first).
- `./gradlew :android:assembleDebug` — the Android app; on an emulator, `python3 android/devlogin.py` signs it in.
- `xcodebuild -project apple/Veyq.xcodeproj -target Veyq -sdk macosx build CODE_SIGNING_ALLOWED=NO` (or
  `-sdk iphonesimulator`) — the Apple app compiles. On a fresh checkout run `./gradlew :core:assembleVeyqCoreDebugXCFramework`
  once first: Xcode looks for the framework before the project's own pre-build step can make it.

## Traps

- The Apple primitives are libsodium 1.0.22 through cinterop (`core/sodium/`): `build_libsodium.py` builds the three
  arm64 slices from the release tarball, refused unless it matches the pinned SHA-256. GitHub's release CDN answers a
  504 now and then; the script retries, and a tarball already in `build/libsodium/` is used as it is.
- The emulator reaches the developer's machine at 10.0.2.2; the debug build maps every `*.localhost` name there and
  keeps the name in the Host header, since the backend picks the tenant from it. Use the tenant's name
  (`veyq.localhost:8080`), never `10.0.2.2:8080`, as the server.
