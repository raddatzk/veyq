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
in that CR, not before (one consumer now). **Built (Phase 1):** the interop commonizes only with
`kotlin.mpp.enableCInteropCommonization=true` in `src/mobile/gradle.properties` — S1's "no extra flag" held for the
per-target compiles, but `:core:compileNativeMainKotlinMetadata` was red without it (`Unresolved reference 'sodium'`,
measured 2026-11-10), as the `appleMain` metadata compile must have been before (inferred, not measured). `LibsodiumConformanceTest` moves `appleTest` → `nativeTest`, reading the
vectors through `platform.posix` instead of `NSString` (S3's reader), so one test holds every libsodium target.
Cross-compiling from this arm64 Mac is the build; no Linux host is needed to build `core`.

**D2 — libsodium for Linux: Kotlin/Native's own clang and sysroot, in `build_libsodium.py`.** Two slices,
`linux_arm64` and `linux_x64`, built as S2 did: the tarball the script already pins and checks, configured with K/N's
LLVM (`clang`, `llvm-ar`, `lld`) against the sysroot of the gcc toolchain K/N downloads for that target. Why not a
container: the static library must link against the **same old glibc** K/N links the core against (2.25 / 2.19); a
library built in Fedora 44 against glibc 2.42 would pull versioned symbols the final link cannot satisfy — and the
build would need Docker for something that is a compile step. The script finds the toolchain by reading
`konan.properties`' `llvm.macos_arm64.user` and `toolchainDependency.linux_*` (never a spelled path), and fails
naming the Gradle task to run when the toolchain is not downloaded yet. **Built:** the lookup is
`core/konan_toolchain.py`, shared with utf8proc's build; the task is `:core:downloadKotlinNativeDistribution`, which
downloads every target's toolchain (measured against an empty `KONAN_DATA_DIR`: both Linux gcc toolchains arrived),
and `buildLibsodium`/`buildUtf8proc` depend on it, so a fresh machine builds the slices in one Gradle run. The Apple slices stay as they are.
**Limit, stated:** libsodium's AES-256-GCM needs the CPU's AES instructions (`LibsodiumPrimitives` refuses to start
without them, as on Apple). Every x86-64 desktop CPU of the last decade and every arm64 laptop has them (remembered); a **Raspberry Pi 4**
(Cortex-A72 without the crypto extension) does not, and the app says so and does not start. Accepted for this slice
(owner Q1, settled: accepted); native-apps' Open gets the row.

**D3 — `nfc` / `nfkd` on Linux (and later Windows): utf8proc, built from a pinned release like libsodium.**
utf8proc 2.11.3 (JuliaStrings, MIT, maintained — releases through 2025-12-30, **Unicode 17.0.0**: its
`utf8proc_unicode_version()`, measured in the tarball — the refine's "16.0" was wrong), one C file plus its
data, compiled by the same K/N toolchain into a static library and reached by cinterop — through `utf8proc_map` with the
length given and `utf8proc_NFC`'s / `utf8proc_NFKD`'s own options, not those two functions: they read a
NUL-terminated string and so cut a name at an embedded U+0000 (`ArchiveNameTest.theHostileCases` went red on them,
measured). Rejected: **ICU through cinterop** (its symbols are renamed per ICU version — `u_foo_77` — so the
link binds one distribution's ICU, and the K/N sysroot has no ICU headers); **GLib's `g_utf8_normalize`** (would make
the core link GLib, and Windows needs a different answer); **a generated pure-Kotlin implementation** (correct but
ours to maintain — tables, composition exclusions, Hangul — where a maintained C library does it). The source is
the GitHub tag tarball `v2.11.3`, pinned by SHA-256 in the build script; the implementer downloads it, hashes it, and
writes the hash in the script with where it came from (this refine did not compile it: the classifier refused
compiling an unpinned download, rightly). **Its proof:** S3's 12 normalization vectors go green, **and** a new
`NormalizationTest` reads Unicode's `NormalizationTest.txt` for the version utf8proc implements (16.0.0, fetched by
the build script and pinned by SHA-256 like the tarballs — **17.0.0**, see above) and checks every line's NFC and NFKD
columns on the Linux target, and Part 1's rule that every other code point is its own NFC and NFKD. Its bite: a stub `nfc` must turn it red, and so must swapping NFC for NFD.

**D4 — `localWallClock` on Linux: kotlinx-datetime.** `LocalDateTime(...).toInstant(TimeZone.currentSystemDefault())`
gives the JavaScript semantics the `expect` documents: a time in a spring-forward gap moves forward by the gap, an
ambiguous autumn time takes the earlier offset. Out-of-range parts roll over as JavaScript's do, built by date
arithmetic from the first of the month (`LocalDateTime` itself refuses them). kotlinx-datetime is JetBrains',
Apache-2.0, maintained (0.8.0, 2026-05-08); it is **already resolved transitively** (`0.8.0-0.6.x-compat` in
`gradle/verification-metadata.xml`), so the direct dependency names that version and adds no library. Rejected:
`mktime` via `platform.posix` (gap and overlap are implementation-defined, and differ on Windows' CRT). Tests in
`linuxTest` run in Europe/Berlin — kotlinx-datetime on Linux reads the zone from `/etc/localtime`, **not `TZ`**
(measured: with only `TZ` set it read UTC), so `run.py test` links the container's `/etc/localtime` and sets `TZ` for
glibc. The direct dependency names `0.8.0-0.6.x-compat`, the artifact Ktor already resolved: 2026-03-29 02:30 → 03:30+02:00, 2026-10-25 02:30 → +02:00 (the earlier),
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
- **Built (Phase 2), where it differs from the above:**
  - **`Config` is `{device}` only** — the name the device gives itself in the account's register. The data directory,
    the language and the server list the refine named have no reader in the core in this slice; each joins with its
    first one. `veyq_init` answers 0 started, 1 already started, 2 config or host function refused, 3 no AES (Q1);
    `veyq_watch` answers 0 for a request that is no `Watched`.
  - **`DeviceKeys` is the bridge's own `NoDeviceKeys` and forwards nothing.** What its functions carry is the Master
    Key to seal (`sealUnlockable`) and the refresh token to keep (`putSecret`): forwarded as host requests, both would
    cross the bridge, which invariant 6 forbids. So the VM slice's Secret Service is a decision of its own: the core
    reaches libsecret itself (cinterop, the key never leaves the library) or the invariant is revisited — never a
    `HostRequest` carrying a key (§ Not in this CR).
  - **What crosses inward:** the sign-in's `password` and `secretKey` are what the person typed, on their way to the
    core — the one place something secret crosses, and never back. § Phase 2's secret-free case is therefore strict for
    everything the core sends (`Answer`, `Update`, `HostRequest`) and, inward, lets through exactly those two fields by
    owner and name; three identifiers that only share a word (`Gate.Signed.connectionKey`, `BarTab.key`,
    `TrustedDevice.wrapId`) are let through by owner and name too, each with its reason, and the test is red when an
    exception names a field the wire no longer has. Bytes in a JSON field are refused as well (they travel beside it).
  - **A `PotView` is sent as what it shows.** Its constructor takes a session, so it cannot be annotated: core's
    `PotView.AsShown` writes its `PotView.Shown` (every row with the core's words for it — line, badge, balance, facts,
    who pays whom, category label) under the view's own serial name, and refuses to read one back. The words are a
    `Notice` (catalog key + each argument tagged by kind, `NoticeSerializer`) and `Piece` (tagged by kind).
  - **The models' watches are their state flows** (`SessionModel.gate`, `ShellModel.state` — the open tenant's, so a
    switch moves the watch —, `PotModel.state`), not their `watch` wrappers: the bridge is Kotlin.
  - **A host request blocks its core thread** until `veyq_reply` (30 s, then the store call fails); the model is built
    on a core thread after `veyq_init` returns, so the store's first read never waits on the thread that started it.
  - **The library hides what it links in:** `-Wl,--exclude-libs,ALL` keeps libcurl, its OpenSSL, libsodium and utf8proc
    out of the dynamic symbol table — without it `libveyq.so` defined 11,348 symbols (OpenSSL's whole API among them),
    which a host whose own libraries load a libcrypto would bind against. With it: 12 — the six and Kotlin/Native's
    `libveyq_symbols`, `IsInstance`, `Konan_cxa_demangle`, `__cxa_demangle`, `_init`, `_fini` (measured, `run.py bridge`).

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
**Built (Phase 2):** the walk is `bridge/src/jvmMain/.../Bindings.kt` (`Schema` + `Bindings.rust`); the tests read the
same `Schema`. The six `extern "C"` declarations are generated from `bridge.h` itself, so the header is the one
hand-written source of the C surface. A type's Rust name is its serial name's last part (the last two where that part
is taken twice: `ShellModelState`, `PotModelState`); a field that holds its own type directly is boxed (`Shell.back`),
one inside a list is not. `WireTest` writes one value of **every variant of every sealed type the roots reach** (45
samples into `src/mobile/build/generated/bridge/samples/<RustType>-<n>.json`), reads each back to the same JSON and
holds it to the descriptor field by field; a Pot's view, sent and never read back, is written as its `Shown` and held
to the view's descriptor. The Rust half ran once in a scratch crate (serde 1.0.229 and serde_json 1.0.151 from the
local cargo cache, `--offline`): `cargo test` read all 45 samples back field for field, `cargo clippy --all-targets -D
warnings` clean — Phase 3 makes it the package's own test.
**Built (Phase 3):** `bridge.rs` carries no inner attribute — an `include!`d file may hold none, so the app's `wire`
module carries the lints' allowances — and ends with a generated `reserialize(rust_type, json)`, one case per wire type:
the app's `every_sample_the_core_wrote_reads_back_field_for_field` reads every sample through it and compares the JSON
written back with the JSON read as values, so a sample of a type the host lacks (bitten: `Ghost-0.json`, "no wire type
Ghost") and a field the host's type drops (bitten: `Config` with an extra field) are red without a list in the test.

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
**Built (Phase 3):** the package is a library (`src/lib.rs`: `bridge`, `words`, `theme`, `create_action`, each `pub`)
and the app (`src/main.rs`), so what the screens will use is no dead code before they exist; the hand-written `bridge`
module is `src/bridge/mod.rs`, which keeps Phase 3's `bridge\.rs$` check about the generated file only. `build.rs`
reads `VEYQ_GENERATED` (`src/mobile/build/generated`, mounted) and `VEYQ_LIB_DIR`, which `run.py` sets — not the
launcher, which only calls `run.py`. `run.py` gained `build` (generation on the host — `:bridge:generateBindings`,
`:bridge:jvmTest --tests '*WireTest*'` for the samples, the two generators — then `cargo clippy --locked --all-targets
-- -D warnings`, `cargo test --locked`, `cargo build --locked` in the container), `run [--port]` (gtk4-broadwayd on
display :5, published on 127.0.0.1:8095) and `lock` (`cargo update`, the one writer of `Cargo.lock`); the cargo
registry and target live in the Docker volumes `veyq-linux-cargo` / `veyq-linux-target`. The app links the bridge's
debug library (`linkDebugShared<Target>`, which `:bridge:jvmTest` links anyway). The Containerfile gained `clippy`
(Fedora's `rust` ships none: `rpm -q clippy` "not installed", measured) and `glibc-langpack-en`/`-de` (D10). The
launcher's tasks are `linux:build`, `linux:run`, `linux:coreTest` (`run.py test`) and `linux:bridge`: infra/README.md
§ The launcher names no task `test`, so a bare `./gradlew test` aggregates nothing — the CR's `linux:test` would have.
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
**Built (Phase 3):** `--gettext DIR` writes `veyq.pot`, `en.po`, `de.po` and `words.rs` into one directory
(`src/mobile/build/generated/linux/words`); `build.rs` compiles each `.po` with `msgfmt --check` into
`OUT_DIR/locale` and the app binds there. The per-key functions take `&str` / `i64`; `say(key, &[Arg])` reaches them by
key (a `Notice`), and `KEYS` lists them. A third glibc line, `bind_textdomain_codeset(…, "UTF-8")`, keeps the words
UTF-8 whatever the locale's charset. **glibc's gettext ignores `LANGUAGE` under a C locale, C.UTF-8 included**
(measured: `LANG=C.UTF-8 LANGUAGE=de gettext --context=drawer_open -d veyq "Open menu"` answered the English, and the
words test was red on every German key); a desktop never runs under C, the container now does not either
(`glibc-langpack-en`/`-de`, `run.py` sets `LANG=en_US.UTF-8`), so Phase 4's `LANGUAGE=de` holds. The app's refusal of
an AES-less CPU (Q1) needed a word: `start_no_aes` joins the catalog (its wording is the implementer's — the owner's to
review).

**D11 — Theme: libadwaita CSS generated from the web's tokens.** `generate_theme.py --gtk <dir>` writes `style.css`
and `style-dark.css` (libadwaita loads both from the application's resource base path by itself), setting
libadwaita's own named colours from the web's roles (`--accent-bg-color`, `--accent-color`, `--window-bg-color`, …,
the mapping a table in the generator) and the radii; and the app icon (`me.raddatz.veyq.svg`, its symbolic variant)
from the brand mark. Never committed, as on the other platforms. libadwaita's widgets keep their own shapes — the
roles colour them (ADR 0123 decision 9: the platform's look over the web's).
**Built (Phase 3), where it differs:** libadwaita 1.9 **deprecates `style-dark.css`** (its binary carries "The resource
%s is deprecated and shouldn't be used anymore. Use style.css with media queries instead.", measured with `strings` in
the image), so `--gtk DIR` writes one `style.css` whose dark roles sit under `@media (prefers-color-scheme: dark)`. No
radii: libadwaita 1.9's stylesheet declares one length variable, `--window-radius` (its `gresource extract`, measured),
the window's own shape, which the platform keeps. The mapping (`GTK_ROLES`) follows Android's scheme: the web's accent is
libadwaita's accent, danger is destructive and error; the web's primary (green, creating) has no libadwaita slot. The
icon is `icons/scalable/apps/me.raddatz.veyq.svg` and `icons/symbolic/apps/me.raddatz.veyq-symbolic.svg`; `build.rs`
bundles the directory with `glib-compile-resources` under `/me/raddatz/veyq`, the app registers it before it starts.

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
**Built (Phase 3):** the Rust is read through `scripts/rust_lexer.py` (`kotlin_lexer` reads a lifetime `'a` as a char
literal). linux-literal covers the setter of each property too (`set_subtitle`, `set_tooltip_text`, …) and
`heading`/`body` (an `AdwAlertDialog`'s), with the literal bare, as `Some(…)`, borrowed or a `format!` template;
linux-po reads the directory, not `git ls-files`. linux-block also refuses GNOME's add icon in any other Rust file (as
apple-block refuses a bare "+"), and demands a `gtk::MenuButton` beside the `gtk::Button`; linux-floating-button sees a
button named in `add_overlay`'s argument or a variable the file binds to one (a `Button` or the block's
`create_action(…)`). Twelve bites, each red with its rule's line and green on the restore, two controls green (a
literal in a comment beside a lifetime; a `Label` overlaid), and both guards red on an empty directory.

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
byte red — the existing `aWrongPrimitiveIsCaught`, now on Linux too); `VEYQ_LIVE=http://veyq.localhost:8080
run.py test --tests '*LiveSignInTest*'` signs in on Linux as the seeded principal (the backend's tenant from the
`Host` header: the origin keeps its name, `*.localhost` resolves to the container's loopback, where socat forwards the
port to `host.docker.internal` — so the command is the JVM's and Apple's, and every tenant of the account arrives under
its own name; `host.docker.internal` as the origin would have sent the wrong `Host`); `/usr/bin/python3 scripts/validate_build_input_pins.py` and `validate_image_pins.py` green.

### Phase 2 — `:bridge`

The module, the six functions, the host function with `DeviceStore` and an unavailable `DeviceKeys` (D6), the
states made serializable, the generator writing `bridge.rs` (D7). `bridge.h` is written once by hand — six
declarations — and checked against the K/N-generated `libveyq_api.h` by a test (the same names and signatures; the
generated header is K/N's and changes with it).

**Done when:** `./gradlew :bridge:jvmTest` green — the dispatch over a door the test plays (sign-in, the shell, a Pot,
Search, through `Call`s, the answers and watch updates read back; **built:** the played door reaches the session only —
`ShellModel` and `PotModel` take a `TenantSession`, which no test can build without a server — so the shell's, Search's
and the Pot's dispatch is held live: `LiveBridgeTest` with `VEYQ_LIVE`, and `run.py bridge` with `VEYQ_LIVE` from C in
Fedora; the owner question Q2 below), every registered root round-tripped, and the
**secret-free schema** case (no generated type carries a field named like a key, seal, token, secret or password;
its bite: a `@Serializable` test state with a `sealKey: ByteArray` turns it red); `:bridge:linkReleaseSharedLinuxArm64`
links, and `nm -D libveyq.so` (in the container) exports exactly the six `veyq_*` functions beside K/N's runtime
symbols; `./gradlew :core:jvmTest :core:macosArm64Test` green and both apps compile (`:android:assembleDebug`, the
`xcodebuild` compile of `src/mobile/CLAUDE.md`) — the annotations change no behaviour.

### Phase 3 — The Linux app's ground: generators, guards, the window

`generate_words.py --gettext` (D10), `generate_theme.py --gtk` (D11), the Cargo package with the bridge wrapper and an
empty `AdwApplicationWindow` that starts the core (D8), `create_action.rs`, the guard rules (D12), `repo_layout.MOBILE_LINUX`,
the launcher tasks (`settings.gradle.kts` + `launcher/`, per `infra/README.md` § The launcher).

**Done when** (`linux:test` is `linux:coreTest`, D8 § Built)**:** `run.py build` green with `cargo clippy --locked -- -D warnings` and `cargo test --locked` (the Rust
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
  for `DeviceKeys` (staying signed in, the device trust — reached by the core itself, never by a host request that
  carries a key: D6 § Built), portals (file chooser, save), drag and drop, and the
  **Flatpak** on the GNOME runtime (with what it must carry: `libcrypt.so.1`, the CA bundle, the zoneinfo).
- **Windows:** `mingwX64`, Ktor's WinHttp engine, the C# writer of D7, WinUI 3 — needs a Windows host.
- Every other application's screens on Linux (Depot, Memories), uploads, writes in a Pot.
- A CI job for the apps (already open in native-apps); this CR's checks run locally.

## Owner questions

- **Q2 — A door for the shell and the Pot in `:bridge`'s test (Phase 2, open).** D6's done-when wanted the dispatch of
  the shell, Search and a Pot tested over a door the test plays; `SessionModel` has one (`Door<A, T>`), but
  `ShellModel(tenant: TenantSession, …)` and `PotModel(tenant: TenantSession, …)` take the concrete session, whose
  constructor is internal and whose account needs a sign-in. Built instead: the played door for the way in
  (`DispatchTest`, six cases), and the shell, Home's groups, Search and three Pots over the real core against the dev
  instance (`LiveBridgeTest`; `run.py bridge` signs in from C on Linux). Options: (a) accept the live proof for those
  calls (they are one line each onto a model the core's own tests hold); (b) give `ShellModel` and `PotModel` a door
  as `SessionModel` has — a core refactor that changes what Swift and Kotlin construct (`ShellModel(tenant:)` is how
  both apps build it); (c) a fake server under a real `TenantSession` (a played backend: sign-in, grants, sealed nodes
  and ledgers) — heavy. Recommendation: **(a)** now; (b) when a second host (Windows) makes the dispatch worth a door of
  its own. **Settled 2026-11-10 (technical, the refiner's): (a).** Each of those calls is one line onto a model whose
  rules the core's own tests hold (`ShellModelTest`, `PotViewEntriesTest`, …); what the bridge adds — the wire, the
  dispatch's way in, the registry, threads — is held without a server. `LiveBridgeTest` skips itself without
  `VEYQ_LIVE`, so a phase whose Done-when leans on it states that it ran with `VEYQ_LIVE` set. The Windows CR revisits (b).

- **Q1 — CPUs without AES (Raspberry Pi 4).** The suite's AES-GCM is libsodium's, which needs the CPU's AES
  instructions; the Linux app would not start on a Pi 4 (the web works there). Recommendation: accept for now, keep it
  on native-apps' Open list, revisit if anyone asks for it (a software AES-GCM would be a second implementation of a
  primitive, held to the same vectors). **Settled (owner 2026-11-10): accepted** — the app refuses such a CPU with a
  message, and native-apps' Open carries the row.

## Retrospective

### Phase 1 (2026-11-10)

Technical:
- **A spike's green is the green of the tasks it ran.** S1 compiled every *target* and concluded the interop
  commonizes; the shared source sets' *metadata* compiles (what the IDE and Phase 2's `:bridge` read) were red until
  `kotlin.mpp.enableCInteropCommonization=true`. Gradle had printed "CInterop Commonization Disabled" all along — a
  configure-time warning is part of a spike's result.
- **A version a refine names is read from the artifact.** utf8proc 2.11.3 implements Unicode 17.0.0, not 16.0; the
  tarball's `utf8proc_unicode_version()` said so in one grep, and the conformance file follows it.
- **A C API's string convention is a semantic.** `utf8proc_NFC` reads up to the first NUL, so an embedded U+0000 cut
  a name; the existing commonTest `ArchiveNameTest` caught it on its first Linux run — running the whole common suite
  on the new target (the `test.kexe` runs all 173 tests, not only the Linux ones) paid for itself at once.
- **kotlinx-datetime on Linux ignores `TZ`** and reads `/etc/localtime`: the wall-clock test went red with `TZ` alone.
  On a desktop that is the system's zone, as the web's; a container has none until it is linked.
- **The backend's tenant comes from `Host`, so a container keeps the origin's name.** A socat forward on the
  container's loopback keeps `VEYQ_LIVE=http://veyq.localhost:8080` the same command on every platform and reached the
  second tenant (`verein.localhost`) unasked; `host.docker.internal` as the origin would have sent the wrong `Host`.
- `:core:downloadKotlinNativeDistribution` downloads every target's toolchain (measured with an empty
  `KONAN_DATA_DIR`), so the C builds depend on it instead of failing on a fresh machine.

Process:
- The pinned-download recipe (download, hash, write the hash into the script, then let the script refuse a mismatch)
  passed the classifier without friction — the refuse-on-mismatch script is what makes a download a build input.
- Docker's CLI plugins (buildx) are not found under a scratch `DOCKER_CONFIG`; `cliPluginsExtraDirs` pointing at
  `~/.docker/cli-plugins` restores them (machine-local, for the memory file rather than the repo).
- A guard over tracked files does not see a new file until it is staged: `validate_image_pins.py` counted the same 26
  references before `git add` and 27 in 11 files after — its first green said nothing about the `Containerfile`.
- The empty-Gradle-home write (ADR 0123) was not a formality: over the warm write's 559 lines it added 90 more —
  parent POMs and BOMs (`jackson-base`, `guava-parent`, `junit-bom`, `kotlinx-coroutines-bom`) that a warm cache
  never fetches and a fresh CI runner would. `GRADLE_USER_HOME=<empty> ./gradlew --write-verification-metadata sha256
  …` over the Linux, metadata, JVM-test and macOS-test tasks, 6 min 18 s.

### Phase 2 (2026-11-10)

Technical:
- **A shared library is not done until a host has called it.** `:bridge:jvmTest` was green and the library linked, yet
  only a C program built against `bridge.h` alone and run in Fedora (`run.py bridge`, `bridge/smoke/smoke.c`) showed the
  six functions, the callbacks across threads and the host's store answered from the host's own thread working — and,
  with `VEYQ_LIVE`, a sign-in over the library's own libcurl reaching the shell's lists.
- **Static libraries leak through a shared one.** The first `nm -D` listed 11,348 defined symbols: everything of
  libcurl, OpenSSL, libsodium and utf8proc, beside the six. `--exclude-libs,ALL` brought it to 12; the check now
  compares the whole list (bridge.h's six + Kotlin/Native's), so a dropped flag is red (bitten).
- **The serialized form of a computed view is what it shows, and only one way.** `PotView` holds a session and derives
  its rows; annotating it was impossible, a copy in `:bridge` forbidden. A serializer in core that writes its `Shown`
  keeps the words the core's (each row's line and badge travel with it) and makes the direction explicit: the round trip
  holds a sent-only type to its descriptor instead of reading it back.
- **"No key crosses" and "the host keeps the keys" collide at `DeviceKeys`.** The refine had the host answer every
  `DeviceKeys` call; those calls carry the Master Key and a refresh token. Phase 2 forwards none; the VM slice inherits
  the choice (D6 § Built).
- **An inward secret is still a secret on the wire.** The secret-free case as written would have refused the sign-in
  itself (`password`, `secretKey`); it is now strict outward and names the two typed-in fields inward, by owner.
- **A done-when can name a door the code does not have.** `ShellModel` and `PotModel` take a concrete `TenantSession`;
  the played door covers the way in only, the rest is held live — Q2.

Process:
- Gradle's `--rerun` is a task option: `./gradlew :core:jvmTest :core:macosArm64Test --rerun` re-ran only the last task
  and reported the first `UP-TO-DATE`; each task takes its own `--rerun`.
- The Rust half could be tried before Phase 3 with no download: serde was in the local cargo cache, so a scratch crate
  with `--offline` compiled the generated `bridge.rs` and read every sample. Reading the first output had already shown
  two generator defects no Kotlin test sees (`pub` on an enum variant's fields, a `Box` inside a `Vec`); the crate proved
  the fixed output, and `theGeneratedRustBoxesOnlyWhatHoldsItselfDirectly` keeps the second.
- A host-side test helper that decodes every update failed on the one type that is sent only (`PotView`): the test's
  own read must be the host's (JSON), not the core's.
- A new module that resolves nothing new still owes the empty-home write, as evidence: `GRADLE_USER_HOME=<empty>
  ./gradlew --write-verification-metadata sha256 :bridge:jvmTest :bridge:generateBindings
  :bridge:linkReleaseSharedLinuxArm64 :bridge:linkReleaseSharedLinuxX64 :bridge:compileCommonMainKotlinMetadata
  :bridge:compileLinuxMainKotlinMetadata` (2 min 34 s, 1.4 GB fetched) left `gradle/verification-metadata.xml`
  unchanged — everything `:bridge` resolves, `:core` already had.

### Phase 3 (2026-11-10)

Technical:
- **A platform's deprecation is read from the platform, not the docs a refine remembers.** D11's `style-dark.css` is
  deprecated in libadwaita 1.9; one `strings` over the library said so, and `gresource extract` of its own stylesheet
  gave the variables it declares (one radius only). Reading the binary the app links beat any page about it.
- **gettext's language switch is off under C, and C.UTF-8 is C.** The words test was green in English and red on every
  German key — English is the `msgid`, so an English-only pass proves nothing about the catalogs. A test of a
  translation layer has to assert a language whose words differ from the fallback.
- **`include!` cannot carry an inner attribute.** The Phase 2 scratch crate compiled `bridge.rs` as a file module; the
  package includes it, so the generator dropped its `#![allow]` and gained the round trip's door (`reserialize`) —
  the derivation the CR wanted ("a root added without a case is red") had to be generated, or the Rust test would have
  kept a list of its own.
- **The CR's own check can collide with the code's names.** `git ls-files … | grep 'bridge\.rs$'` would have matched
  the hand-written `bridge` module; it lives in `src/bridge/mod.rs`, so the check keeps meaning "the generated file".
- Crates against the no-deprecated rule (2026-11-10, crates.io API and each repository's API): gtk4 0.11.5 (gtk-rs/gtk4-rs,
  not archived, pushed 2026-11-10), libadwaita 0.9.2 (GNOME World/Rust/libadwaita-rs, active 2026-09-26), glib 0.22.10
  (gtk-rs/gtk-rs-core, released 2026-09-19), async-channel 2.5.0 (smol-rs, not archived, pushed 2026-07-13), serde
  1.0.229 and serde_json 1.0.154 (serde-rs, not archived, pushed 2026-10-16). `Cargo.lock` locks 77 packages; the
  transitive ones (gtk-rs' sys crates, futures-core, event-listener, …) were not read one by one — cargo has no
  deprecation gate, and the rule's check on them is still a reading, not a guard.

Process:
- A `test` verb in the launcher would have broken its own rule (no task named `test`): the CR named the task from
  `run.py`'s verb, not from infra/README.md § The launcher. A launcher task is named against that section first.
- The bite harness for a guard pair is one scratch script: inject, run the real guard as a subprocess, restore from a
  snapshot copy, assert green again after each bite — fourteen cases in under a minute, the restore itself checked.
- `run.py build` re-run after only a Rust edit took ~25 s (Gradle up to date, cargo incremental in its volume); the first
  cold one ~50 s plus the image's 75 s for `clippy` and the langpacks.

