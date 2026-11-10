---
id: cr-core-as-a-native-library-and-the-linux-app
title: The core as a Kotlin/Native library behind one narrow bridge, proved by the Linux app's first slice in Rust and libadwaita
status: in-progress
satisfies: []
adrs: ["0127", "0123", "0122", "0125"]
invariants:
  - the suite on Linux is libsodium's and meets contracts/crypto/vectors/v1.json with zero failures, as Apple's does; no primitive is written for Linux
  - every rule stays the core's — the bridge only carries; the Rust app holds drawing and the device, nothing else (ADR 0127 decision 1)
  - the bridge's Rust types are generated from the core's models and never committed or written by hand (ADR 0127 decision 5)
  - no word in the Rust app but from src/mobile/words/words.json, no colour but from the web's tokens
  - Android and the Apple app behave exactly as before; `./gradlew :core:jvmTest :core:macosArm64Test` stays green in every phase
  - no key material and no plaintext cross the bridge beyond what the UI shows (names, amounts, a tile's bytes) — never a key, a seal or a token
---

## Goal

ADR 0127 decisions 4 and 5 (revised 2026-11-10): the core joins Linux and Windows as a Kotlin/Native shared library
reached through **one narrow bridge**, and Linux draws its UI in Rust with gtk4-rs and libadwaita-rs. This CR builds
the bridge and proves it with the Linux app's first slice: **sign-in, the shell, a Pot read, Search**. Windows reuses
the bridge in a later CR (it needs a Windows host). What stays open after this CR is listed in § Not in this CR.

The slice, as the other apps draw it (ADR 0122; [native-apps](../docs/crosscutting/native-apps.md) § Platforms, the
iPad/macOS row is the closest model): an `AdwNavigationSplitView` whose sidebar carries **the one switcher** (spaces
and tenants, at its top), **New**, **Home** with its folder and contents groups and its filters, **Favorites** and
**Recent**, the **applications**, and **Settings** pinned at its foot (its rows do nothing yet, as on the other apps);
the content side shows Home, an application's list, an object; a **Pot** opens read-only (entries, balances, people);
**Search** over names, as the apps search today (`navigation/ShellWords.kt`'s hits). Collapsed below a breakpoint, as
GNOME's own apps do (§ Decisions, D9).

## Context & constraints

- **Substrate.** Refined on branch `claude/native-app-shape` at `624d36d0f` (156 commits ahead of `main`, which
  has no `src/mobile/`). The implementation starts from that branch or from `main` once it carries it.
- **Read first:** ADR 0127 (whole), ADR 0123 decisions 1, 3, 7, 9 and its Consequences on the Apple build,
  `src/mobile/CLAUDE.md` (build, checks, traps), [native-apps](../docs/crosscutting/native-apps.md).
- **Builds on this Mac, runs in a container.** Everything Kotlin cross-compiles from macOS arm64 (Kotlin/Native
  2.4.21 ships `macos_arm64-linux_arm64` / `-linux_x64` toolchains, `konan.properties`). The Rust app is built and run
  inside an arm64 **Fedora 44** container; GTK's **Broadway** backend draws it into a browser (the browser pane).
  The real desktop session — Secret Service, portals, Flatpak — is a later slice in a Fedora ARM VM (the owner's UTM
  setup), § Not in this CR.
- **Device trust is not in this slice.** Without a Secret Service the Linux host answers `DeviceKeys` as
  unavailable, so every start is the full sign-in — exactly the core's path on the ad-hoc-signed macOS build
  (`src/mobile/CLAUDE.md` § Traps). Staying signed in arrives with the VM slice.

## Spikes run at refine (2026-11-10, on this Mac, Kotlin 2.4.21, Docker 29.9.2)

Each was run on a throwaway edit of `src/mobile/core` that was reverted afterwards; nothing of it is committed.

| # | Question | Command / setup | Result |
|---|---|---|---|
| S1 | Does the core compile for Linux unchanged? | `linuxArm64()`, `linuxX64()` added to `core/build.gradle.kts`, `LibsodiumPrimitives.kt` moved `appleMain` → `nativeMain`, `ktor-client-curl` in `linuxMain`, `nfc`/`nfkd`/`localWallClock` stubbed; `./gradlew --write-verification-metadata sha256 :core:compileKotlinLinuxArm64 :core:compileKotlinLinuxX64 :core:compileKotlinMacosArm64 :core:compileKotlinJvm` | green. All of `commonMain` compiles for both Linux targets; the `sodium` cinterop commonizes into `nativeMain` (no extra flag); Apple and JVM unchanged. The metadata write added 559 lines to `gradle/verification-metadata.xml` (the `*-linuxarm64`/`*-linuxx64` variants and `ktor-client-curl`). |
| S2 | libsodium for Linux: K/N's toolchain or a container? | libsodium 1.0.22 from the pinned tarball, `./configure --host=<triple>` with `CC=~/.konan/dependencies/llvm-21-aarch64-macos-essentials-97/bin/clang`, `AR=…/llvm-ar`, `CFLAGS="-O3 --target=<triple> --sysroot=<konan gcc toolchain>/<triple>/sysroot --gcc-toolchain=<toolchain> -fPIC"`, `LDFLAGS=… -fuse-ld=lld` | both slices in 52 s on this Mac, no container: `aarch64-unknown-linux-gnu` (glibc 2.25 sysroot) and `x86_64-unknown-linux-gnu` (glibc 2.19), 137 / 141 objects, ML-KEM-768 headers present. |
| S3 | Does the suite on Linux meet the vectors? | `./gradlew :core:linkDebugTestLinuxArm64`, then `docker run --platform linux/arm64 … fedora:44 test.kexe --ktest_filter='*Conformance*'` with `VEYQ_VECTORS` mounted, over libsodium and an **identity** `nfc`/`nfkd` | 172 cases ran. The only 12 reds are the normalization cases (`name_folding` slugify/fileNameSlug on decomposed input, `slug_token`, `report_bundle body`), so the vectors catch a wrong `nfc`/`nfkd` and will hold the real one. `crypto_aead_aes256gcm_is_available()` = 1 after `sodium_init` under Docker's arm64 VM. |
| S4 | Ktor on Linux native: which engine, TLS? | `HttpClient(Curl)` (`io.ktor:ktor-client-curl:3.6.1`) in the shared library, called through the bridge from the Rust spike in Fedora 44 | `GET /api/setup` on the dev backend (`host.docker.internal:8080`, `Host: veyq.localhost:8080`) → 200 with the JSON. Over HTTPS to a public host the TLS handshake and CA check passed. libcurl is **linked in statically**; `ldd libveyqcore.so` needs only glibc libs, zlib, `libresolv` — and `libcrypt.so.1`, which Fedora 44 has only with `libxcrypt-compat` (installed in the container). |
| S5 | The bridge: a C surface Rust calls, answers on a core thread | `@CName("veyq_call")` taking a JSON request (kotlinx.serialization), a context pointer and an answer function pointer, answering from `Dispatchers.Default`; Rust `extern "C"` declaration, `async_channel` into GTK's main loop, `glib::spawn_future_local` | the Adwaita window (`AdwToolbarView`, `AdwStatusPage`, a boxed list) under `gtk4-broadwayd` showed both answers (the `/api/setup` JSON and 8 random bytes from libsodium), seen in the browser pane at `127.0.0.1:8095`. gtk4 0.11.5, libadwaita 0.9.2 (features `v4_20`, `v1_8`), glib 0.22.10, async-channel 2.5.0; rustc 1.99.1 from Fedora 44; `cargo build --release` 47 s. |
| S6 | What does the whole-core export cost? | `binaries.sharedLib` on `:core` itself | `libveyqcore.so` 35.6 MB, `libveyqcore_api.h` 27,971 lines: every public class as handles and function tables — the export ADR 0127 decision 5 rejects. The `:bridge` module (D4) exports only its own `@CName` functions. |

Also found by S1: `./gradlew :core:compileCommonMainKotlinMetadata` was red on the untouched branch (`ExifReader`'s
XMP regexes used `RegexOption.DOT_MATCHES_ALL`, which the common stdlib lacks; no check runs that task). Fixed before
this CR, with `XmpValueTest` pinning the multi-line reads — the task is green now and Phase 2's `:bridge` may depend on it.

## Decisions

**D1 — Targets and source sets.** `core` gains `linuxX64()` and `linuxArm64()`; `mingwX64()` joins with the
Windows CR. Kotlin's default hierarchy already gives the source sets: **`nativeMain`** (Apple + Linux, later
Windows) takes `LibsodiumPrimitives.kt` and the `sodium` cinterop (commonized, S1); `appleMain` keeps what is
Foundation's; **`linuxMain`** holds Linux's `actual`s and the Curl engine. The Windows CR adds `mingwMain`; whatever
Linux and Windows share then (`nfc`/`nfkd` over utf8proc, `localWallClock`) moves to an intermediate set of their own
in that CR, not before (one consumer now). `LibsodiumConformanceTest` moves `appleTest` → `nativeTest`, reading the
vectors through `platform.posix` instead of `NSString` (S3's reader), so one test holds every libsodium target.
Cross-compiling from this arm64 Mac is the build; no Linux host is needed to build `core`.

**D2 — libsodium for Linux: Kotlin/Native's own clang and sysroot, in `build_libsodium.py`.** Two slices,
`linux_arm64` and `linux_x64`, built as S2 did: the tarball the script already pins and checks, configured with K/N's
LLVM (`clang`, `llvm-ar`, `lld`) against the sysroot of the gcc toolchain K/N downloads for that target. Why not a
container: the static library must link against the **same old glibc** K/N links the core against (2.25 / 2.19); a
library built in Fedora 44 against glibc 2.42 would pull versioned symbols the final link cannot satisfy — and the
build would need Docker for something that is a compile step. The script finds the toolchain by reading
`konan.properties`' `llvm.macos_arm64.user` and `toolchainDependency.linux_*` (never a spelled path), and fails
naming the Gradle task to run when the toolchain is not downloaded yet. The Apple slices stay as they are.
**Limit, stated:** libsodium's AES-256-GCM needs the CPU's AES instructions (`LibsodiumPrimitives` refuses to start
without them, as on Apple). Every x86-64 desktop CPU of the last decade and every arm64 laptop has them (remembered); a **Raspberry Pi 4**
(Cortex-A72 without the crypto extension) does not, and the app says so and does not start. Accepted for this slice
(owner Q1, settled: accepted); native-apps' Open gets the row.

**D3 — `nfc` / `nfkd` on Linux (and later Windows): utf8proc, built from a pinned release like libsodium.**
utf8proc 2.11.3 (JuliaStrings, MIT, maintained — releases through 2025-12-30, Unicode 16.0), one C file plus its
data, compiled by the same K/N toolchain into a static library and reached by cinterop (`utf8proc_NFC`,
`utf8proc_NFKD`). Rejected: **ICU through cinterop** (its symbols are renamed per ICU version — `u_foo_77` — so the
link binds one distribution's ICU, and the K/N sysroot has no ICU headers); **GLib's `g_utf8_normalize`** (would make
the core link GLib, and Windows needs a different answer); **a generated pure-Kotlin implementation** (correct but
ours to maintain — tables, composition exclusions, Hangul — where a maintained C library does it). The source is
the GitHub tag tarball `v2.11.3`, pinned by SHA-256 in the build script; the implementer downloads it, hashes it, and
writes the hash in the script with where it came from (this refine did not compile it: the classifier refused
compiling an unpinned download, rightly). **Its proof:** S3's 12 normalization vectors go green, **and** a new
`NormalizationTest` reads Unicode's `NormalizationTest.txt` for the version utf8proc implements (16.0.0, fetched by
the build script and pinned by SHA-256 like the tarballs) and checks every line's NFC and NFKD columns on the Linux
target. Its bite: a stub `nfc` must turn it red, and so must swapping NFC for NFD.

**D4 — `localWallClock` on Linux: kotlinx-datetime.** `LocalDateTime(...).toInstant(TimeZone.currentSystemDefault())`
gives the JavaScript semantics the `expect` documents: a time in a spring-forward gap moves forward by the gap, an
ambiguous autumn time takes the earlier offset. Out-of-range parts roll over as JavaScript's do, built by date
arithmetic from the first of the month (`LocalDateTime` itself refuses them). kotlinx-datetime is JetBrains',
Apache-2.0, maintained (0.8.0, 2026-05-08); it is **already resolved transitively** (`0.8.0-0.6.x-compat` in
`gradle/verification-metadata.xml`), so the direct dependency names that version and adds no library. Rejected:
`mktime` via `platform.posix` (gap and overlap are implementation-defined, and differ on Windows' CRT). Tests in
`linuxTest` run with `TZ=Europe/Berlin`: 2026-03-29 02:30 → 03:30+02:00, 2026-10-25 02:30 → +02:00 (the earlier),
month 13 and day 0 rolling over — each value taken from what `new Date(…)` gives in Node with that `TZ`, written into
the test with the command that produced it. JVM and Apple keep their `actual`s (unchanged behaviour, invariant 5).

**D5 — Ktor on Linux: the Curl engine.** `ktor-client-curl` at the Ktor version the core already pins. CIO is
rejected: it has no TLS on Kotlin/Native. Curl is linked in statically (S4); CA certificates are the system's (the
Fedora container's bundle verified; the Flatpak slice checks the GNOME runtime's). The one dynamic dependency it adds,
`libcrypt.so.1`, is named in `src/mobile/CLAUDE.md` § Traps and installed in the dev container (`libxcrypt-compat`);
the Flatpak slice checks the GNOME runtime has it. `VeyqAccount.outside`'s rule (no bearer to anyone else) holds on
Linux unchanged: it is the core's, not the engine's — `LiveSignInTest` on Linux proves the bearer path, the
existing commonTests the outside path.

**D6 — The bridge: a module `:bridge` whose only public surface is six C functions.** `src/mobile/bridge/`, a Kotlin
Multiplatform module with the Linux targets (and `mingwX64` later) plus `jvm()` for its tests and its generator,
depending on `:core` as an **implementation** dependency (so the shared library exports only `:bridge`'s own
`@CName` functions — S6). Built as `binaries.sharedLib { baseName = "veyq" }` → `libveyq.so`. The C surface:

| Function | What it does |
|---|---|
| `veyq_init(config, host, host_ctx)` | Starts the core once: a JSON config (the app's data directory, its language, the server list the host keeps) and the **one host function** the core calls for everything only the device does (below). |
| `veyq_call(request, bytes, len, ctx, answer)` | One action: a serialized `Call` (a sealed class — `Session.SignIn`, `Shell.Open`, `Search.Query`, `Pot.Open`, …), optional binary input; answered once, asynchronously, through `answer(ctx, json, bytes, len)` on a core thread. |
| `veyq_watch(request, ctx, update) → id` | Observes one screen model's state (`SessionModel`, `ShellModel`, `PotModel`, the search hits): every new state arrives serialized through `update(ctx, json, bytes, len)`; the first arrives at once. Maps onto each model's existing `watch` (ADR 0127 decision 2). |
| `veyq_unwatch(id)` | Ends a watch (the model's `Watch` is cancelled). |
| `veyq_reply(token, json, bytes, len)` | The host's answer to one host request (below). |
| `veyq_shutdown()` | Cancels the core's scope; the host calls it when the application quits. |

- **The host side, one function the other way.** The core sends the host a serialized `HostRequest` with a reply
  token — `DeviceStore.get/put/remove`, `DeviceKeys.*` (answered "unavailable" in this slice), and the file reads a
  later slice adds (`UploadSource`, download sinks, `MediaUpload.Deriver`'s decoding) — and the host answers through
  `veyq_reply`, on any thread. The core's interfaces (`session/TrustMarks.kt` `DeviceStore`, `session/DeviceTrust.kt`
  `DeviceKeys`, `memories/MediaUpload.kt` `Deriver`) get one `:bridge` implementation each that forwards; nothing in
  `:core` learns about the bridge. The HTTP client and the clocks are the core's own on Linux (Curl, kotlinx), so the
  host provides neither.
- **Serialization: the core's kotlinx.serialization JSON**, UTF-8, plus an optional byte payload beside it for bytes
  the UI shows (an avatar, a cover, a tile) so they never travel as base64. A pointer handed to a callback is valid
  only during that call — the host copies what it keeps; the core copies what the host hands in before returning.
  This is the rule each side's wrapper enforces, written in both `bridge.h`'s comment and the Rust wrapper's.
- **The wire types are the core's models, made serializable — not a copy in `:bridge`.** Today none of the screen
  models' states is `@Serializable` (`SessionModel.Gate`, `ShellModel.State` with `Shell` and `Lists`,
  `PotModel.State` with `PotView`: measured, 0 annotations in those files). This CR annotates them and what they hold;
  a field that is not data the UI draws (a function, a session, a key) is not part of a state — where one sits there
  today it moves out of the state or is `@Transient` with the reason, and **a key, a seal or a token is never
  serializable** (§ Phase 2's secret-free schema case holds that). `:bridge` holds only the `Call` and `HostRequest` sealed classes, the
  dispatch from a `Call` to the model's function, and the model registry (an open Pot is a `PotModel` under an id).
  The dispatch is mechanical: a `when` over the call's type with one line per case; a rule found there is a defect to
  move into the core (ADR 0127 decision 1).
- **Threads.** Every answer and update arrives on a core thread (`Dispatchers.Default`); the Rust wrapper hands it to
  GTK's main loop through an `async_channel` and never touches a widget off it (S5's pattern) — the same rule as
  Swift's `@Sendable` trap in `src/mobile/CLAUDE.md`.

**D7 — The Rust (later C#) types are generated from the serial descriptors, by `:bridge` itself.** A JVM task
`:bridge:generateBindings` walks the `SerialDescriptor`s of the roots `:bridge` registers (`Call`, `HostRequest`, each
watched state, each answer) and writes **`bridge.rs`**: `serde` structs and enums with the same field names,
`#[serde(tag = "type")]` for sealed classes as kotlinx.serialization's class discriminator writes them, optional
fields as `Option<T>`, plus the six `extern "C"` declarations. The C# writer joins it in the Windows CR, from the same
walk. No JSON Schema step and no third-party generator: the descriptor is the model, and the walk is ~200 lines whose
output both sides compile against. The output goes to `src/mobile/build/generated/bridge/` and is **never committed**
(as the theme and the words); the Rust build reads it through an environment variable the launcher task sets.
Its proof is two-sided: a `:bridge` jvmTest round-trips one value of every registered root through
kotlinx.serialization and through `serde` (a Rust test fed the JSON the JVM wrote — the generated `bridge.rs`
compiles and deserializes it field for field), and a root added without a case is red by derivation (the test
enumerates the registered roots, not a list of its own).

**D8 — The Rust app lives in `src/mobile/linux/`.** A Cargo package (`veyq`, edition 2024) with a committed
`Cargo.lock` and `cargo build --locked`: gtk4 0.11, libadwaita 0.9 (features `v4_20`, `v1_8`: GTK 4.22, libadwaita
1.9 are what Fedora 44 carries — measured in the spike image: `gtk4-4.22.5`, `libadwaita-1.9.4`; the GNOME runtime the Flatpak slice picks must carry them too), async-channel 2, serde/serde_json — each checked against the
no-deprecated rule at implement time (cargo has no deprecation gate; the implementer reads each crate's repository
status and writes the date in the CR's retro). `build.rs` links `libveyq.so` from the `:bridge` output. Modules:
`bridge` (the generated types, the safe wrapper over the six functions, the host function), `words` (D10), `theme`
(D11), `create_action.rs` (D12), and one module per screen: `sign_in`, `shell` (the split view, sidebar, switcher),
`home`, `pot`, `search`. A **`Containerfile`** beside it (Fedora 44 by digest, `validate_image_pins.py` holds the pin;
`gtk4-devel libadwaita-devel rust cargo gcc gettext libxcrypt-compat`) is the build and run environment;
**`linux/run.py`** (Python, per the repository's rule) builds the image, runs `cargo` in it with the bridge library
and the generated sources mounted, and runs the app under `gtk4-broadwayd` with the port published on 127.0.0.1 only
— the one entry point the launcher's tasks (`./gradlew linux:build`, `linux:run`, `linux:test`) call.
`scripts/repo_layout.py` gains `MOBILE_LINUX`; every guard and script names the path through it
(`validate_mobile_words.py` today spells `ROOT / "src" / "mobile"` itself — it moves onto `repo_layout` in the same
phase).

**D9 — The layout.** GNOME HIG, as libadwaita's own widgets draw it: `AdwNavigationSplitView` (sidebar 240–360 sp,
as macOS's split view), an `AdwBreakpoint` at `max-width: 600sp` collapsing it to a navigation stack (remembered as the value libadwaita's
own examples use — checked against the GNOME HIG at implement time), the switcher a **`GtkMenuButton` as the sidebar header bar's title** (the title is the
current space, its menu lists spaces and tenants — the same model `SessionModel`'s switcher gives the other apps),
**Search** a `GtkSearchEntry` in the sidebar under the header bar, toggled by Ctrl+F (GNOME's convention), Settings
an `AdwActionRow` pinned at the sidebar's foot. Both sides of the breakpoint are part of the browser pass (§ Phase 4:
1100 × 750 and 500 × 750). Home's groups are `AdwPreferencesGroup`-style boxed lists; a Pot's balances and entries
the same, read-only.

**D10 — Words: a gettext catalog generated from `words.json`.** `generate_words.py --gettext <dir>` writes
`veyq.pot` and `de.po` / `en.po` (`msgctxt` = the key, `msgid` = the English, `msgstr` = the language's), and the
build compiles them with `msgfmt` into the GResource / locale tree; plurals map CLDR `one`/`other` to
`Plural-Forms: nplurals=2; plural=(n != 1);` for both languages, and the generator **refuses** a language whose
categories it cannot map (the day a third language comes, that is a decision, not a silent wrong plural). It also
writes `words.rs`: one function per key with typed arguments (as the Kotlin `Msg` and Swift's `say` do), calling
`glib::dpgettext2("veyq", key, english)` and filling `{n}` placeholders. `bindtextdomain`/`textdomain` are glibc's,
declared as two `extern "C"` lines — no gettext crate. A `Notice` the core raises arrives as its key and arguments
(ADR 0127 decision 1) and is said by the same generated function.

**D11 — Theme: libadwaita CSS generated from the web's tokens.** `generate_theme.py --gtk <dir>` writes `style.css`
and `style-dark.css` (libadwaita loads both from the application's resource base path by itself), setting
libadwaita's own named colours from the web's roles (`--accent-bg-color`, `--accent-color`, `--window-bg-color`, …,
the mapping a table in the generator) and the radii; and the app icon (`me.raddatz.veyq.svg`, its symbolic variant)
from the brand mark. Never committed, as on the other platforms. libadwaita's widgets keep their own shapes — the
roles colour them (ADR 0123 decision 9: the platform's look over the web's).

**D12 — The guards learn Linux.** `validate_mobile_words.py` gains **linux-literal**: no Rust string literal with
letters handed to a widget's shown text — `.title(`, `.subtitle(`, `.label(`, `.description(`, `.tooltip_text(`,
`.placeholder_text(`, `set_title(`, `set_label(`, `Label::new(Some(`, `Button::with_label(`, `adw::Toast::new(`,
`ActionRow::builder()…title(` — and **linux-po**: no `.po` / `.pot` file tracked under `src/mobile/linux/`.
`validate_mobile_platform_guidelines.py` gains **linux-block**: `create_action.rs` exists and builds the creating
action as a header-bar / sidebar button with `list-add-symbolic` (and a menu where a level creates several kinds), and
**linux-floating-button**: no other file adds a `gtk::Button` through `Overlay::add_overlay` (GNOME has no floating
action button). Each rule is bitten at implement time by injecting one violation into a scratch copy and reading it
red, then green on the restore (`changes/README.md` § Bite-proof a guard by snapshot-and-restore). Both guards fail
when they read no Rust source (as they do for Kotlin and Swift).

## Inventory (locators — run at implement time, not counts)

| Area | Locator |
|---|---|
| What `appleMain` holds that Linux needs an answer for | `find src/mobile/core/src/appleMain -name '*.kt'` (refine: 7 files — only `LibsodiumPrimitives.kt` moves; the others are Apple's host code, whose Linux counterparts are the host requests of D6 or a later slice) |
| The `expect`s | `grep -rn '^expect ' src/mobile/core/src/commonMain` |
| States to make serializable | `grep -n 'class State\|sealed interface Gate\|class Shell\b\|class Lists\|class PotView' -r src/mobile/core/src/commonMain`, then the closure of what each holds |
| The models a watch reaches | `grep -rln 'fun watch(' src/mobile/core/src/commonMain` |
| Host interfaces | `grep -rn '^interface\|^    interface' src/mobile/core/src/commonMain --include='*.kt' \| grep -v /api/` |
| The words and theme generators' outputs | `grep -n 'add_argument' src/mobile/words/generate_words.py src/mobile/theme/generate_theme.py` |
| Paths the guards spell | `grep -n 'src" / "mobile\|MOBILE' scripts/*.py` |
| Docs that name the platforms | `grep -rln 'Linux\|linuxX64\|linuxArm64\|mingwX64' docs/ src/mobile/CLAUDE.md infra/README.md` |

## Phases

### Phase 1 — The core on Linux

`core` gains the Linux targets (D1), `build_libsodium.py` the Linux slices (D2), a utf8proc build beside it with
`nfc`/`nfkd` (D3), `localWallClock` (D4), the Curl engine (D5), `LibsodiumConformanceTest` in `nativeTest`, the
normalization and wall-clock tests in `linuxTest`; `gradle/verification-metadata.xml` written for the new targets
(`./gradlew --write-verification-metadata sha256` over the tasks that resolve them, from an empty Gradle home, per
ADR 0123's Consequences). `src/mobile/linux/Containerfile` and `run.py`'s `test` verb arrive here, since the Linux
test binary runs only in the container.

**Done when:** `./gradlew :core:jvmTest :core:macosArm64Test` green (unchanged); `python3 src/mobile/linux/run.py test`
runs `:core:linkDebugTestLinuxArm64`'s `test.kexe` in Fedora 44 arm64 with **zero failures** — Conformance at
production cost (all 172 cases, the 12 normalization ones among them), `NormalizationTest` over every line,
the wall-clock cases; `:core:linkDebugTestLinuxX64` links (it is not run: no x64 container on this Mac is a
measurement — say so); the bite for D3 and for the conformance move recorded (a stub `nfc` red, an AEAD off by one
byte red — the existing `aWrongPrimitiveIsCaught`, now on Linux too); `VEYQ_LIVE=http://host.docker.internal:8080
… run.py test --tests '*LiveSignInTest*'` signs in on Linux as the seeded principal (the backend's tenant from the
`Host` header); `/usr/bin/python3 scripts/validate_build_input_pins.py` and `validate_image_pins.py` green.

### Phase 2 — `:bridge`

The module, the six functions, the host function with `DeviceStore` and an unavailable `DeviceKeys` (D6), the
states made serializable, the generator writing `bridge.rs` (D7). `bridge.h` is written once by hand — six
declarations — and checked against the K/N-generated `libveyq_api.h` by a test (the same names and signatures; the
generated header is K/N's and changes with it).

**Done when:** `./gradlew :bridge:jvmTest` green — the dispatch over a door the test plays (sign-in, the shell, a Pot,
Search, through `Call`s, the answers and watch updates read back), every registered root round-tripped, and the
**secret-free schema** case (no generated type carries a field named like a key, seal, token, secret or password;
its bite: a `@Serializable` test state with a `sealKey: ByteArray` turns it red); `:bridge:linkReleaseSharedLinuxArm64`
links, and `nm -D libveyq.so` (in the container) exports exactly the six `veyq_*` functions beside K/N's runtime
symbols; `./gradlew :core:jvmTest :core:macosArm64Test` green and both apps compile (`:android:assembleDebug`, the
`xcodebuild` compile of `src/mobile/CLAUDE.md`) — the annotations change no behaviour.

### Phase 3 — The Linux app's ground: generators, guards, the window

`generate_words.py --gettext` (D10), `generate_theme.py --gtk` (D11), the Cargo package with the bridge wrapper and an
empty `AdwApplicationWindow` that starts the core (D8), `create_action.rs`, the guard rules (D12), `repo_layout.MOBILE_LINUX`,
the launcher tasks (`settings.gradle.kts` + `launcher/`, per `infra/README.md` § The launcher).

**Done when:** `run.py build` green with `cargo clippy --locked -- -D warnings` and `cargo test --locked` (the Rust
half of D7's round trip, and the words module: every key's German and English, a plural on both sides of 1);
`/usr/bin/python3 scripts/validate_mobile_words.py` and `validate_mobile_platform_guidelines.py` green, each new rule
bitten (D12); `scripts/validate_guard_gaps.py` run once (a guard changed); the generated `.po`, CSS and `bridge.rs`
not tracked (`git ls-files src/mobile/linux | grep -E '\.(po|pot|css)$|bridge\.rs$'` empty).

### Phase 4 — The slice: sign-in, the shell, a Pot, Search

Sign-in (server, email, password, the Secret Key prompt the core's `SessionModel` asks for; every start signs in —
§ Context), the shell (D9), Home with its groups and filters, Favorites and Recent, the applications, the switcher, a
Pot read-only, Search. Each screen draws the core's state and calls its actions; nothing else.

**Done when:** a browser pass in the pane against the dev instance (`./gradlew backend:seed`; the seeded principal
signs in; a second tenant from `DevSecondTenantTest` so the switcher has two; the seeded Pots), at **1100 × 750 and
500 × 750** (both sides of the breakpoint): sign in, switch tenant and space, open Home and filter it, open a
Favorite, open a Pot and read its entries, balances and people against what the web shows for the same Pot, search a
name and open the hit, the language German (`LANGUAGE=de`) and English, light and dark (`ADW_DEBUG_COLOR_SCHEME`).
Read the console (the app's stderr in the container) before believing the window. A screenshot of each width is the
proof. `run.py build` and both guards still green.

### Phase 5 — The docs

ADR 0127's **Built** line (decision 4 built for this slice; decision 5's bridge built, its Windows half not),
ADR 0123's Consequences (the Linux targets, libsodium and utf8proc slices, measured), [native-apps](../docs/crosscutting/native-apps.md)
(the Linux row: builds, UI, layout, seen running — Broadway in Fedora 44, 2026-…; Open: the VM slice, Flatpak, AES-less
CPUs), `src/mobile/CLAUDE.md` (`linux/` and `bridge/` in the layout, `run.py` in Prove your work, the traps: K/N's
toolchain download, `libcrypt.so.1`, a callback pointer valid only during the call), `infra/README.md` § The launcher (the new tasks). The published site stays as it is — no Linux app ships to
users in this CR (`site/src/content/docs/security/start-here.md` already says "once they ship").

**Done when:** the trust-chain guards green (`/usr/bin/python3 scripts/validate_*.py` as the `trust-chain` CI job
runs them, `validate_changes.py` among them), and every "measured" in the touched docs names its command.

## Not in this CR

- **The VM slice:** the real GNOME session in the Fedora ARM VM (UTM, `~/.ssh/veyq_vms_ed25519`) — the Secret Service
  for `DeviceKeys` (staying signed in, the device trust), portals (file chooser, save), drag and drop, and the
  **Flatpak** on the GNOME runtime (with what it must carry: `libcrypt.so.1`, the CA bundle, the zoneinfo).
- **Windows:** `mingwX64`, Ktor's WinHttp engine, the C# writer of D7, WinUI 3 — needs a Windows host.
- Every other application's screens on Linux (Depot, Memories), uploads, writes in a Pot.
- A CI job for the apps (already open in native-apps); this CR's checks run locally.

## Owner questions

- **Q1 — CPUs without AES (Raspberry Pi 4).** The suite's AES-GCM is libsodium's, which needs the CPU's AES
  instructions; the Linux app would not start on a Pi 4 (the web works there). Recommendation: accept for now, keep it
  on native-apps' Open list, revisit if anyone asks for it (a software AES-GCM would be a second implementation of a
  primitive, held to the same vectors). **Settled (owner 2026-11-10): accepted** — the app refuses such a CPU with a
  message, and native-apps' Open carries the row.

## Retrospective

(filled by the implementer)
