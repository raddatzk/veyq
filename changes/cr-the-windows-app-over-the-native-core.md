---
id: cr-the-windows-app-over-the-native-core
title: The core as a Windows DLL behind the same bridge, proved by the Windows app's first slice in WinUI 3
status: in-progress
satisfies: []
adrs: ["0127", "0123", "0122", "0125"]
invariants:
  - the suite on Windows is libsodium's and meets contracts/crypto/vectors/v1.json with zero failures, every one of its 172 cases, as Apple's and Linux's do; no primitive is written for Windows
  - every rule stays the core's — the bridge only carries; the C# app holds drawing and the device, nothing else (ADR 0127 decision 1)
  - the bridge stays one — bridge.h, the wire and the dispatch unchanged for Windows; the C# types are generated from the core's models, never committed or written by hand (ADR 0127 decision 5)
  - no word in the C# app but from src/mobile/words/words.json, no colour but from the web's tokens (ADR 0127 decision 3, ADR 0123 decision 7)
  - no key material and no plaintext cross the bridge beyond what the UI shows — never a key, a seal or a token (SecretFreeSchemaTest)
  - Android, the Apple app and the Linux app behave exactly as before; `./gradlew :core:jvmTest :core:macosArm64Test` and `python3 src/mobile/linux/run.py test` stay green in every phase
---

## Goal

ADR 0127 decision 5: Windows joins as WinUI 3 in C# over the core as a Kotlin/Native `mingwX64` DLL, reached through
**the bridge Linux already uses** (`src/mobile/bridge/`). This CR brings Windows to exactly Linux's first slice —
**sign-in, the shell, Home with its groups, a Pot read only, Search** — and nothing past it (owner's order 2026-11-10:
Windows catches up first; device trust and packaging follow for Linux and Windows together; then application by
application).

The slice, as Linux draws it (native-apps § Platforms) but to Fluent's guidelines (ADR 0123 decision 9): a
`NavigationView` whose pane is the sidebar — **the one switcher** (spaces and tenants) at its top, Home, Favorites,
Recent, the applications, and **Settings at its foot** (its rows do nothing yet, as on the other apps); the content
side shows Home (folder and contents groups, its filters), an application's list, an object; a **Pot** opens read only
(entries, overview, people); **Search** over names, as the apps search today (`navigation/ShellWords.kt`'s hits).

## Context & constraints

- **Substrate.** Refined on branch `claude/windows-cr-f6c73c` at `585f2895b` (= `claude/native-app-shape`, Linux's
  first slice done and harvested). The implementation starts from that branch or from whatever carries it then.
- **Read first:** ADR 0127 (whole), ADR 0123's Consequences "the core on Linux", "the bridge", "the Linux app",
  `src/mobile/CLAUDE.md` (the bridge, `linux/`, every trap), [native-apps](../docs/crosscutting/native-apps.md).
  The Linux CR this mirrors was harvested into those; its spikes and decisions are not repeated here.
- **Where what runs.** Everything Kotlin cross-compiles **on this Mac** (Kotlin/Native 2.4.21 has the
  `macos_arm64-mingw_x64` toolchain: `konan.properties`, `targetToolchain.macos_arm64-mingw_x64`); the C# that has
  no Windows API (the bridge's generated types and their round trip) builds and tests on the Mac too (.NET SDK
  10.0.301 there, measured `dotnet --list-sdks`). Everything that needs Windows — running the core's tests, the DLL's
  smoke, the WinUI build, the app and its pass — runs on the **Windows 11 VM** (`ssh -i ~/.ssh/veyq_vms_ed25519
  kevin@192.168.64.8`), driven from the Mac by one script (D9). No Windows host builds Kotlin; no Git on the VM.
- **The VM is ARM64; the app is x64.** Kotlin/Native has no Windows ARM64 target (ADR 0127 decision 5), so the DLL,
  its test binary and the app are x64 processes under Windows' x64 emulation (Prism) there. **No x64 PC is measured
  by this CR** — said so in its docs, as Linux's x86-64 is.
- **Device trust is not in this slice** (ADR 0127's Consequences: the bridge carries no key). Every start signs in, as
  on Linux; the next CR (Linux and Windows together) has the core reach DPAPI/Windows Hello and libsecret itself.

## Measured at refine (2026-11-10)

Throwaway edits of `src/mobile/{core,bridge}` (a `mingwX64()` target, the Linux `actual`s copied to `mingwMain`,
`ktor-client-winhttp` 3.6.1, `--write-verification-metadata sha256`), reverted afterwards; nothing of it is committed.
The VM was only read, but for W4/W5's test binaries, run from a scratch directory on `F:` that was deleted after
(W5 ran after the owner had updated the VM to 26H2, 26300.9711, and after O1–O3).

| # | Question | Command / setup | Result |
|---|---|---|---|
| W1 | Does the core compile for `mingwX64` on this Mac? | `./gradlew --write-verification-metadata sha256 :core:compileKotlinMingwX64 :core:linkDebugTestMingwX64 :bridge:linkReleaseSharedMingwX64` | `:core` compiles (only the existing warnings) and `test.exe` links (19.9 MB). The metadata write added 278 lines (the `*-mingwx64` variants; `ktor-client-winhttp`). `downloadKotlinNativeDistribution` fetched `msys2-mingw-w64-x86_64-2`. |
| W2 | libsodium and utf8proc for mingw, with K/N's own clang? | libsodium 1.0.22 from the pinned tarball (its SHA-256 matched `build_libsodium.py`'s), `./configure --host=x86_64-w64-mingw32` with `CC=<K/N llvm>/clang`, `AR=llvm-ar`, **`RANLIB="llvm-ar s"`** (K/N's LLVM has no `llvm-ranlib`), `--target=x86_64-w64-mingw32 --sysroot=<msys2 toolchain>`; utf8proc 2.11.3's one C file the same way | libsodium in 40 s, 137 objects, ML-KEM-768 headers present; libtool names it `libsodium.lib` (renamed to `.a` for cinterop). utf8proc: one object, 350 KB. |
| W3 | The bridge DLL: does it link, what does it import and export? | `:bridge:linkReleaseSharedMingwX64` with Linux's `-Wl,--exclude-libs,ALL`, then without; `objdump -p veyq.dll` | With the flag: `lld: error: unknown argument: --exclude-libs` (ELF only). Without: `veyq.dll` 11.4 MB; imports only `KERNEL32`, `ADVAPI32`, `WINHTTP`, `msvcrt` (no runtime to install). Exports **541** symbols: the six `veyq_*` and `veyq_symbols`, and libsodium's (`crypto_*`, `sodium_*`), utf8proc's, winpthreads' and the unwinder's. |
| W4 | Does the suite run on the VM under x64 emulation? | `test.exe --ktest_negative_gradle_filter=*Conformance*,*Live*` with `VEYQ_VECTORS` | **166 tests, 0 failures, 0.8 s.** |
| W5 | And the conformance? | `test.exe --ktest_filter=*LibsodiumConformanceTest*`; a C probe against the mingw `libsodium.a` (`sodium_runtime_has_*`, `crypto_aead_aes256gcm_is_available`); a second probe reading `cpuid`, `xgetbv` and `IsProcessorFeaturePresent` itself; libsodium rebuilt with `-mxsave` | First red: `LibsodiumPrimitives` refused to start, the probe said `avx=0 aes256gcm=0` — libsodium's AES-256-GCM needs **AES-NI, PCLMUL and AVX** (`aead_aes256gcm_aesni.c:1013`). The same on 26100.4349, 26200.9711 and 26300.9711. But Prism **does** show AVX: CPUID leaf 1 AVX, OSXSAVE, FMA, F16C, leaf 7 AVX2/BMI1/BMI2, `xgetbv(0)`=7, `PF_AVX`/`PF_AVX2` 1; the file's emulation settings offer "hide newer emulated CPU features", i.e. they are shown by default. The defect was **our build's**: libsodium reads XCR0 only through `HAVE__XGETBV` or `HAVE_AVX_ASM`; its `configure` refuses the second for Windows x64 by design and the first failed under clang (`'__builtin_ia32_xgetbv' needs target feature xsave`), so `has_avx` was 0 on **every** CPU, a real x64 PC's too. With **`-mxsave`** in `CFLAGS`: `checking if _xgetbv() is available... yes`, the probe `avx=1 avx2=1 aes256gcm=1`, and the whole suite **168 tests, 0 failures, 1.4 s — `libsodium conformance: 172 cases`**. → D2. |
| W6 | How does the VM reach the dev backend? | `ssh -R 18181:127.0.0.1:8080 kevin@192.168.64.8` and, in that session, a raw `GET /api/setup` with `Host: veyq.localhost:8080` to `127.0.0.1:18181` | **200** with the setup JSON: unlike Fedora's sshd, the VM's allows remote forwarding (its `sshd_config` sets no `AllowTcpForwarding`). No forwarder on the Mac is needed. `veyq.localhost` does **not** resolve on Windows (`[Dns]::GetHostAddresses` fails; only `localhost` does). → D9. |
| W7 | The VM itself | `Win32_OperatingSystem`, `Get-AppxPackage`, `Get-Command`, `winget search/show`, the registry | Windows 11 Home 10.0.26100 ARM64, 10 logical CPUs, 8 GB, C: 37.9 GB free, F: Dev Drive 54.5 GB free (trusted, Defender's filter attached). x64 emulation present (`xtajit64.dll`). Installed: Windows App Runtime **2.6.5** for ARM64, x86 and **x64**; VCLibs; .NET Framework 4.8.1 (release 533320); `tar.exe`; `UIAutomationClient` loads in PowerShell. Not installed: `dotnet`, `git`, Visual Studio, a real Python (`python.exe` is the Store stub). winget offers .NET SDK 10.0.404, Git 2.56.0.4. Display **1024 × 768** at 96 DPI; culture and UI culture **de-DE**; zone W. Europe Standard Time; apps light. Display off after 300 s, sleep after 900 s, screen saver on. LongPathsEnabled 0. |
| W8 | Sessions | `Get-Process explorer`, `(Get-Process -Id $PID).SessionId` | The owner's desktop is session 1 (logged on, not locked: no `LogonUI`); an ssh command runs in **session 0**, which has no desktop — an app started from it shows nothing. → D9. |

Dependencies read against the no-deprecated rule (2026-11-10): **Ktor's WinHttp engine** 3.6.1 (Ktor's own, released
2026-10-29 with the rest of Ktor, `maven-metadata.xml`); **libsodium** and **utf8proc** — the same pinned releases;
**.NET 10** SDK 10.0.404 (the current LTS, remembered: supported to November 2028; .NET 11 is a release candidate);
**Windows App SDK** 2.6.5 (`Microsoft.WindowsAppSDK.WinUI`, `.Foundation`, published 2026-11-03, no deprecation in
NuGet's catalog); `Microsoft.Windows.SDK.BuildTools` 10.0.28000.2957 (2026-10-28, none); **MSTest** 4.5.2 (MIT,
2026-10-27, none). **System.Text.Json** is the .NET runtime's own: no JSON library is added. No other NuGet package.

## Decisions

**D1 — Targets and source sets.** `core` and `:bridge` gain `mingwX64()`. What Linux and Windows share moves to an
intermediate source set **`bridgedMain`** (the targets reached through the bridge; `bridgedTest` beside it), as the
Linux CR's D1 deferred to this one: `nfc`/`nfkd` over utf8proc and their `utf8proc` cinterop, `localWallClock` over
kotlinx-datetime, `NormalizationTest` and `WallClockTest`. `linuxMain` keeps the Curl engine; `mingwMain` holds the
WinHttp engine. In `:bridge`, `Exports.kt` moves to `bridgedMain` whole; the one difference — the
engine — is an `expect` it calls (`linuxMain` Curl, `mingwMain` WinHttp). Kotlin's default hierarchy template is
extended, not replaced (`applyDefaultHierarchyTemplate { common { group("bridged") { withLinux(); withMingw() } } }`
or what 2.4.21 names for it — read the plugin, do not remember it). Commonized cinterop
(`kotlin.mpp.enableCInteropCommonization=true`) must reach the new set: `:core:compileBridgedMainKotlinMetadata` is
green, not only each target's compile (the Linux CR's trap).
**Built (Phase 1):** 2.4.21 names it `applyHierarchyTemplate(KotlinHierarchyTemplate.default) { group("native") {
group("bridged") { group("linux"); group("mingw") } } }` (read from `kotlin-gradle-plugin-api-2.4.21-sources.jar`,
`KotlinHierarchyDsl.kt`; experimental, opted in), in `:core` and `:bridge` alike, so `linuxMain` → `bridgedMain` →
`nativeMain`. The interop reached the new set with no further flag (`:core:compileBridgedMainKotlinMetadata` green).
`:bridge` has the `mingwX64` target but links no DLL yet — Phase 2's, with its export gate. `LinuxLiveSignInTest` is
`bridgedTest`'s `BridgedLiveSignInTest`; its engine is a test `expect` (`linuxTest` Curl, `mingwTest` WinHttp), as
`Exports.kt`'s `platformHttpClient` is.

**D2 — libsodium and utf8proc for mingw: Kotlin/Native's own clang, in the existing build scripts.** A `mingw_x64`
slice in `build_libsodium.py` and `build_utf8proc.py`, built as W2 did, with `core/konan_toolchain.py` learning the
mingw toolchain from `konan.properties` (`toolchainDependency.mingw_x64`, the target triple — never a spelled path).
Its `RANLIB` is `llvm-ar s`; libtool's `libsodium.lib` is copied as `libsodium.a`. **libsodium's `CFLAGS` carry
`-mxsave`** (W5): without it its CPU detection never reads XCR0 on mingw, finds no AVX on any CPU and refuses AES-GCM.
`-mxsave` only lets the compiler accept the `_xgetbv` intrinsic; it emits no XSAVE instruction of its own, and
libsodium calls `_xgetbv` only after CPUID said OSXSAVE. The script fails the build if its `config.log` does not say
`checking if _xgetbv() is available... yes` — the defect was silent, so its guard is the line that names it. Both
libraries are compiled with their `*_STATIC` define, so their headers mark nothing `dllexport` (D5's export gate
measures whether that is enough). **Trap (measured):** the `cinteropSodium*` task does not take the static library as
an input, so a rebuilt `libsodium.a` left the old one inside the klib and the test binary red until
`:core:cinteropSodiumMingwX64 --rerun`; the script's output is wired as the task's input, or the implementer says why
not.
**Built (Phase 1):** `config.log` writes the check and its result on two lines (`checking if _xgetbv() is available`,
then `result: yes`; the one-line form is `configure`'s stdout), so the script matches the pair. `-DSODIUM_STATIC` is in
the mingw `CFLAGS`. Every `sodium` and `utf8proc` cinterop — Apple's and Linux's too — declares its static library an
input (`libraryInput` in `core/build.gradle.kts`): in the `-mxsave` bite `:core:cinteropSodiumMingwX64` re-ran on the
rebuilt library without `--rerun`, and again on the restore (measured, `windows/run.py test`'s Gradle lines). The
verification-metadata write from an empty Gradle home added 288 lines (`git diff --stat`); the empty home was given
only the wrapper's Gradle distribution (its download timed out; it is no dependency).

**D3 — Every primitive on Windows is libsodium's, AES-256-GCM among them** (owner 2026-11-10, Q1: one source per
platform, as Apple's and Linux's). libsodium's AES-GCM needs AES-NI, PCLMUL and AVX. Every x64 CPU with AVX has it.
Windows on ARM runs the x64 app under Prism, which shows x64 apps AVX by default (W5, measured on the VM; the default
came with Prism's October 2025 update, remembered from Microsoft's emulation-settings page and the press, and a user
can switch it off per app with "hide newer emulated CPU features"). Refused at start with `veyq_init`'s `3`, as the
Pi 4 is on Linux: an x64 CPU without AVX (some low-end Atom, Celeron and Pentium Silver parts, remembered), and a
Windows-on-ARM PC whose Prism does not show AVX (not updated since that release, or that switch set for veyq).
The app says so and does not start. Rejected: CNG for AES-GCM alone (runs everywhere, but two sources of primitives
on one platform, where every platform has one); a software AES-GCM of our own (the Pi 4 decision's reason).

**D4 — HTTP is Ktor's WinHttp engine.** TLS is Schannel's and the CA store the system's, so the DLL carries no
OpenSSL (W3's imports: `WINHTTP` and nothing to install). WinHTTP takes its proxy from `netsh winhttp`, not the
user's browser settings (remembered; a proxy is out of this slice's scope and named in native-apps' Open if found).

**D5 — The bridge's Windows half: the same `bridge.h`, a DLL whose exports are exactly the six.** `:bridge` links
`veyq.dll` for `mingwX64`. W3 exported 541 symbols; on Windows an export does not interpose (a DLL's imports bind by
module, not by name, unlike ELF's), so this is hygiene, not the security reason it is on Linux — still the surface
stays `bridge.h`'s six and K/N's `veyq_symbols`, held by a gate in `run.py bridge` that reads the export table
(`objdump -p`, Apple's LLVM reads PE). The means are measured, not chosen here: D2's `*_STATIC` defines, then lld's
MinGW `--exclude-all-symbols` or the `.def` K/N writes beside the DLL (`veyq.def`, W3: exactly the seven).
`BridgeHeaderTest` also holds `bridge.h` against K/N's mingw header (`veyq_api.h`). `bridge/smoke/smoke.c` is
cross-compiled for mingw on the Mac against `bridge.h` alone and run on the VM (winpthreads is in the sysroot).
**Q2 of the Linux CR stays (a):** a second host adds no dispatch — the C# app calls the same `Bridge.kt` — so the shell's
and the Pot's dispatch keep their live proof (`LiveBridgeTest`) and get no door.
**Built (Phase 2):** the restriction is K/N's own `veyq.def` handed to the linker
(`linkerOpts(outputDirectory.resolve("veyq.def").absolutePath)`): with a `.def` lld exports what it names and no longer
exports every symbol, which it does when nothing is marked `dllexport` — and nothing is, the six included. Measured
(`objdump -p`): without the `.def`, **185** exports (D2's `*_STATIC` defines had already removed libsodium's and
utf8proc's from W3's 541; left were winpthreads', the unwinder's, iconv's); `--exclude-all-symbols` alone removed every
export, the six too (a 2.1 MB DLL with no export table); the `.def` alone, from an empty output directory, exactly the
seven. The DLL imports `ADVAPI32`, `KERNEL32`, `msvcrt`, `WINHTTP`. K/N's mingw header names its C API entry
`veyq_symbols`, with the functions' own prefix, so `BridgeHeaderTest` drops it by name. The smoke links against an
import library written from that `.def` with a `LIBRARY veyq.dll` line added (`lld -flavor link /lib /def:`; without the
line the exe imports from a DLL with an empty name) and links winpthreads statically (`-static -lpthread`): `smoke.exe`
imports only `KERNEL32`, `msvcrt` and `veyq.dll`. `smoke.c` is unchanged.

**D6 — The C# types are generated beside the Rust ones.** `Bindings.kt` gains a C# writer over the same `Schema`
walk: one file, `build/generated/bridge/Bridge.g.cs` (never committed), with
- a `record` per class, its fields under their serial names;
- an `abstract record` per sealed class with `[JsonPolymorphic(TypeDiscriminatorPropertyName = "type")]` and one
  `[JsonDerivedType]` per variant;
- an enum per enum with `[JsonStringEnumMemberName]`, and `?` as a nullable reference;
- a `JsonSerializerContext` over every root, so System.Text.Json is source-generated and reflection-free;
- the six functions as `[LibraryImport("veyq")]` declarations written from `bridge.h`, as the Rust `extern "C"` lines are.

The names reuse Rust's short-name resolution. The wire's `explicitNulls`/`encodeDefaults` put every field on the
wire; whether STJ needs `AllowOutOfOrderMetadataProperties` (the discriminator not first) is answered by the round
trip, not assumed.

The C# side is `src/mobile/windows/Veyq.Bridge` — a `net10.0` class library with no Windows API. It holds the safe
wrapper over the six functions, the host function with the device store as a JSON file under
`%LOCALAPPDATA%\veyq\` (Linux's file store; no secret is in it), and the hand-off of every answer to the UI thread
through a `DispatcherQueue` the app gives it. Callbacks are `[UnmanagedCallersOnly]` statics behind function
pointers, with a `GCHandle` as `ctx`: per call freed after its answer, per watch kept until `veyq_shutdown`, as
`bridge.h` asks. `Veyq.Bridge.Tests` (MSTest) reads every sample in `build/generated/bridge/samples`, deserializes it
into its generated type and writes it back equal — the Rust test's round trip — **on the Mac and on the VM**.
**Built (Phase 2):**
- A field's C# property is its serial name (`pot`, `minorUnits`), with `[JsonPropertyName]` beside it; a C# keyword is
  Pascal-cased (`ref` → `Ref`), since an `@ref` property breaks System.Text.Json's source generator (CS1525 in its
  output, measured with SDK 10.0.301). Pascal names for every field were rejected: `Update.Pot`'s `pot` would be named
  as its class (CS0542); the generator fails on that collision.
- Variants are `sealed record`s nested in their `abstract record`, and every type is named from `global::` (a nested
  `Update.Shell` hides the wire's `Shell`). Each variant is registered in `WireContext` under its own
  `TypeInfoPropertyName` (`AnswerDone`, `HostReplyDone`): the source generator names metadata by the simple name, and
  variants of different sealed classes share one (SYSLIB1031).
- `bridge.h`'s typedefs are file-scoped `using unsafe` aliases of `delegate* unmanaged[Cdecl]<…>`; a `const char *` is a
  `byte*` (the wrapper writes NUL-terminated UTF-8 and copies what arrives with `Marshal.PtrToStringUTF8`).
- `AllowOutOfOrderMetadataProperties` is not needed: every sample reads back without it (the core writes `type` first).
- **The hand-off to the UI thread is a `SynchronizationContext`, not a `DispatcherQueue`** — that type is WinUI's, a
  Windows API the library must not have. `Core.CallAsync` returns a `Task` that never continues on the core's thread
  (`RunContinuationsAsynchronously`), so awaited on the UI thread it resumes there; a watch posts each update to the
  context it was started on. WinUI's UI thread carries its `DispatcherQueue`'s context (remembered; Phase 3 measures).
- `Veyq.Bridge.Tests`: `RoundTripTest` over the samples (46 files, `ls build/generated/bridge/samples | wc -l`),
  `DeviceStoreTest`, and `CoreTest` — Windows only (`[OSCondition]`), the wrapper over the real DLL as the smoke runs it
  from C: start, a second start refused, the Session watch, `Session.Start` done, the sign-in gate, the store asked,
  Search refused before a tenant, an ended watch silent, shutdown.

**D7 — The app: WinUI 3, unpackaged, x64, self-contained.** `src/mobile/windows/Veyq` targets
`net10.0-windows10.0.26100.0` (`win-x64`), with `WindowsPackageType=None` (MSIX is the next CR),
`WindowsAppSDKSelfContained=true` and `SelfContained=true`, so the build output runs where it is copied, without the
installed App Runtime and without an x64 .NET runtime. It references `Microsoft.WindowsAppSDK.WinUI`,
`.Foundation` and `Microsoft.Windows.SDK.BuildTools`, not the metapackage, which pulls AI, ML, Search and Widgets
(its 2.6.5 dependency list). Whether those suffice for a build is Phase 3's first measurement. Packages are central
(`Directory.Packages.props`, exact versions), locked (`packages.lock.json` committed, every restore `--locked-mode`),
from nuget.org alone (`nuget.config` with package source mapping); `global.json` names SDK `10.0.300` with
`rollForward: latestFeature` (the Mac's 10.0.301 and the VM's 10.0.404 both qualify). **The deprecation gate:**
`run.py build` fails on any package `dotnet package list --deprecated` or `--vulnerable` names (`dotnet list package`
on the SDK, whichever it is — measured, not remembered). .NET has the gate cargo lacks.
**Built (Phase 2, the packages and the gate; the app is Phase 3's):**
- MSTest is `MSTest.TestFramework` and `MSTest.TestAdapter` 4.5.2 under the Microsoft.Testing.Platform runner
  (`global.json`'s `test.runner`, `EnableMSTestRunner`), **not the `MSTest` metapackage**: that adds
  `Microsoft.Testing.Extensions.CodeCoverage` 18.12.0 (no licence expression in NuGet's catalog, only the placeholder
  URL of a licence file; not read further) and `Microsoft.NET.Test.Sdk` (VSTest's host, which the runner does not
  need) — read from the catalog entries of `mstest/4.5.2` and of each. What they pull is in the lock file: Microsoft.Testing.Platform and its MSBuild part,
  `Extensions.Telemetry` (and `Microsoft.ApplicationInsights` 2.23.0 under it), `TrxReport.Abstractions`,
  `TestPlatform.ObjectModel`, `MSTest.Analyzers`. Telemetry is off for every `dotnet` `run.py` runs
  (`TESTINGPLATFORM_TELEMETRY_OPTOUT`, `DOTNET_CLI_TELEMETRY_OPTOUT`), and its words are English
  (`DOTNET_CLI_UI_LANGUAGE`: dotnet writes UTF-8 where PowerShell reads CP850).
- The test project lists `RuntimeIdentifiers` `win-x64`, so its lock file is one for the Mac's restore and the VM's
  `--arch x64` run.
- **Trap (measured):** a property that implies an SDK package makes the lock file the SDK's. `IsAotCompatible` adds
  `Microsoft.NET.ILLink.Tasks` at the SDK's runtime version — 10.0.9 under 10.0.301, 10.0.15 under 10.0.404 — and the
  VM's locked restore was red (NU1004). It was dropped; Phase 3's self-contained app must not set trimming or AOT
  properties either, or the lock file has to come from one SDK.
- The gate is `dotnet package list --deprecated` and `--vulnerable`, `--include-transitive --no-restore --format json`
  (`dotnet package list` is the 10.0 SDK's verb; without `--no-restore` it restores on its own). It runs **on the Mac**,
  not the VM: the registry's marks are the same from either host and the lock file is one, and the JSON is read in
  Python. NuGet's own audit already refuses a vulnerable package at restore (NU1903 as an error under
  `TreatWarningsAsErrors`); the gate adds the deprecated ones.

The shell, to Fluent (ADR 0123 decision 9; what Windows' own apps do — Settings, Explorer, Photos):
- **`NavigationView`** with `PaneDisplayMode="Auto"`. Its own thresholds decide the form, not Linux's 600 sp: Left from
  1008 epx, LeftCompact from 641, LeftMinimal below.
  - The pane holds Home, Favorites, Recent and the applications as menu items. Its `IsSettingsVisible` item is
    **Settings at its foot**.
  - Its `PaneHeader` holds **the switcher**: a `DropDownButton` whose `MenuFlyout` lists the core's tenants and
    spaces.
  - Its `AutoSuggestBox` is **Search**: hits as suggestions, a chosen one opened; Ctrl+E and Ctrl+F focus it.
  - Its back button follows the content `Frame`.
- **New** is the first command of the content's `CommandBar` (Explorer's place), built only in `CreateAction.cs` —
  the creating action's one home, as `create_action.rs` and `CreateAction.kt` are. It offers nothing yet, as on Linux.
- **Home**: a `SelectorBar` for its filters over one grouped `ListView` (folders, contents).
- **A Pot**: a `SelectorBar` (Entries, Overview, People) over its lists.
- **An object the slice does not draw** says so with the core's `not_built`.
- The window has a Mica backdrop and the system's light or dark, never one of its own. The debug build alone honours
  `VEYQ_THEME` and `VEYQ_LANGUAGE` for the pass.
- Every interactive control carries an `AutomationProperties.AutomationId` (D9 drives by it) and its words as
  `AutomationProperties.Name` where it shows no text.

At 1024 × 768 (W7) the three forms are reachable: maximized (1024 wide, Left), 800 (LeftCompact), 500 (LeftMinimal).
Just below and above each threshold — 1000/1010 and 635/645 — is the pass's arithmetic.

**D8 — Words, money, days, sizes, theme.**
- **Words.** `generate_words.py --resw <dir>` writes `Strings/en-US/Resources.resw` and `Strings/de-DE/Resources.resw`
  and `Words.g.cs`, one method per key (ADR 0127 decision 3), into `build/generated/words/windows` (never committed).
  `.resw` has no plurals, so a plural key becomes one entry per CLDR category (`key#one`, `key#other`), and
  `Words.g.cs` picks the category by a rule the generator writes per language. It refuses a language whose rule it
  cannot write, as `--gettext` refuses one it cannot map. Placeholders `{n}` / `{n:int}` are filled by name in
  `Words.g.cs`, never by `string.Format` positions. MRT Core reads `resources.pri` beside the unpackaged exe
  (remembered; Phase 3 measures it).
- **Money, days and sizes** are the system's:
  - money through `Windows.Globalization.NumberFormatting.CurrencyFormatter(code)`, which writes any currency with
    its own symbol in the user's locale (remembered; Linux writes a foreign currency by its code, glibc's limit);
  - days as the culture's short date;
  - sizes through `StrFormatByteSizeEx` (Explorer's).

  Each is tested in two cultures, on the VM.
- **Theme.** `generate_theme.py --winui <dir>` writes one `Theme.xaml` ResourceDictionary: the web's brand roles as
  the app's accent (`SystemAccentColor` and its light/dark steps), light and dark under `ThemeDictionaries`;
  everything else is Fluent's own. **No icon in this slice**: an `.ico` needs rasterized sizes, and MSIX needs its own
  PNG assets — both are the packaging CR's.

**D9 — Driving the VM from the Mac: `src/mobile/windows/run.py`.** One entry point, verbs as Linux's:
- `test [--tests <p>]` — links `:core:linkDebugTestMingwX64`, copies `test.exe` with the vectors and
  `NormalizationTest.txt` to the VM, and runs it there; its exit code is the binary's.
- `bridge` — links `veyq.dll` and runs the export gate (on the Mac) and the smoke (on the VM).
- `build` — generates the C# bridge, the samples, the words and the theme, and links the DLL. Then
  `dotnet test Veyq.Bridge.Tests` on the Mac, and on the VM `dotnet build`, the deprecation gate and `dotnet test`.
- `run [--devlogin] [--width W --height H]` — the app in the owner's session.
- `drive <steps>` — D9's driver.

What makes it work, each from a measurement:
- **The tree goes by `tar` over ssh** (the VM's in-box `tar.exe`, W7) into `F:\veyq\`, a Dev Drive. The substrate is
  exactly the Mac's working tree the run starts from; no Git on the VM. PowerShell goes as `-EncodedCommand` and its
  output is read as CP850 (`run.py` decodes it).
- **The backend** is the Mac's dev instance, reached as the Mac reaches it — `http://veyq.localhost:8080`. `run.py`
  holds `ssh -N -R 8080:127.0.0.1:8080` open for the run (W6), and the VM's `hosts` maps `veyq.localhost` and every
  seeded tenant's name to `127.0.0.1` (owner step O2, since Windows resolves no `*.localhost`). The backend picks the
  tenant from the Host header, which WinHTTP sends from the URL.
- **The owner's session.** ssh is session 0 (W8), so the app and the driver start in session 1 through a scheduled
  task registered *interactive only* (`schtasks /Create /IT /RU <the owner>`) and run on demand (`/Run`). Whether a
  key-authenticated ssh logon may register such a task is Phase 3's first measurement; if not, the owner registers the
  two tasks once (O5). `--devlogin` writes the seeded principal (`VEYQ_ENV_FILE`, as Linux's) to a file on the VM
  that the start wrapper reads into the app's environment and deletes, never onto a command line. The password is
  never typed.
- **Seeing and driving: UI Automation, text first** (the owner's rule: text before screenshots). `src/mobile/windows/
  Veyq.Drive` is a console tool (`net10.0-windows`, `UIAutomationClient` from the Windows Desktop runtime, no
  package). It runs in session 1 like the app, takes its steps from a file, and writes its results back:
  - `tree` — the window's UIA tree as text: control type, name, AutomationId, value, enabled, bounds;
  - `invoke <id>`, `select <id>`, `set <id> <text>`, `key <keys>`, `size <w> <h>`, `wait <id>` — drive it;
  - `shot <file>` — a PNG of the window, for the final proof only.

  The app writes its log (core notices it surfaces, unhandled exceptions) to `%LOCALAPPDATA%\veyq\log.txt`, which
  `run.py` fetches before anything on screen is believed. Fallback, not plan: the UTM window on the Mac through
  computer use (`app_screenshot` of UTM), if UIA cannot reach a control — that would be an accessibility defect to
  fix, too.

**D10 — Guards.**
- `validate_mobile_words.py`: **windows-literal** — no C# or XAML literal as shown text (`Text=`, `Content=`,
  `Header=`, `PlaceholderText=`, `Title=`, `AutomationProperties.Name=` in XAML; the same properties assigned in C#);
  **windows-resw** — no `.resw` under `src/mobile/windows/`.
- `validate_mobile_platform_guidelines.py`: **windows-block** — the creating action only in `CreateAction.cs`;
  **windows-floating-button** — no button laid over a view.
- `repo_layout.MOBILE_WINDOWS`. The launcher gets `windows:coreTest`, `windows:bridge`, `windows:build` and
  `windows:run` (`launcher/windows/`, `settings.gradle.kts`), as Linux's. Each new rule is bitten red and green on
  the restore (snapshot-and-restore, never `git checkout`), and `validate_guard_gaps.py` runs once.

## Owner steps on the VM (each an approval; nothing was installed at refine)

**Approved by the owner (2026-11-10, Q2) and done the same day:** O1 (`dotnet --list-sdks` → 10.0.404; ARM64 runtimes
10.0.15 with WindowsDesktop; `C:\Program Files\dotnet\x64\dotnet.exe --list-runtimes` → Microsoft.NETCore.App
10.0.15), O2 (`[Dns]::GetHostAddresses('veyq.localhost')` → 127.0.0.1; the old file kept as `hosts.veyq-backup`),
O3 (`powercfg /query` → 0 for both, `ScreenSaveActive` 0). O5 is `run.py`'s, in Phase 3.

- **O1 — .NET 10 SDK, ARM64**: `winget install --id Microsoft.DotNet.SDK.10 --version 10.0.404 --architecture
  arm64`, and the **x64 .NET 10 runtime** beside it (`winget install --id Microsoft.DotNet.Runtime.10 --architecture
  x64`, the version matching the SDK's runtime). `Veyq.Bridge.Tests` loads the x64 `veyq.dll`, so its test host must
  be x64 (`dotnet test --arch x64`). The app is self-contained and needs neither.
- **O2 — `hosts`**: `127.0.0.1 veyq.localhost verein.localhost` (and any other seeded tenant's name) in
  `C:\Windows\System32\drivers\etc\hosts`.
- **O3 — No blank screen during a pass**: display-off and sleep "never" on AC (`powercfg /change monitor-timeout-ac 0`,
  `standby-timeout-ac 0`) and the screen saver off. Otherwise a screenshot after five idle minutes is black.
- **O4 — Not needed:** Git, Visual Studio, Python, the Windows SDK installer, the App Runtime (2.6.5 is there and the
  app carries its own), and long paths (`F:\veyq\` keeps them short). If Phase 3 finds the WinUI build needs anything
  beyond the .NET SDK and the NuGet packages, it stops and asks.
- **O5 — The scheduled tasks** `veyq-app` and `veyq-drive` (interactive only, as the owner), registered by `run.py` —
  or by the owner once, if a key-based ssh logon may not (D9).
- **Optional:** a larger UTM display for nicer screenshots. 1024 × 768 reaches all three forms (D7).

## Inventory (locators — run at implement time, not counts)

| Area | Locator |
|---|---|
| What `linuxMain`/`linuxTest` hold that Windows shares | `find src/mobile/core/src/linux* src/mobile/bridge/src/linuxMain -name '*.kt'` |
| Where the build names Linux targets or slices | `grep -n 'linux\|Linux' src/mobile/core/build.gradle.kts src/mobile/bridge/build.gradle.kts src/mobile/core/sodium/build_libsodium.py src/mobile/core/utf8proc/build_utf8proc.py src/mobile/core/konan_toolchain.py` |
| The AES-GCM seam | `grep -n 'aes\|Aes' src/mobile/core/src/nativeMain/kotlin/me/raddatz/veyq/mobile/crypto/LibsodiumPrimitives.kt src/mobile/core/src/commonMain/kotlin/me/raddatz/veyq/mobile/crypto/*.kt` |
| The Rust writer the C# one mirrors | `grep -n 'fun \|rustNames' src/mobile/bridge/src/jvmMain/kotlin/me/raddatz/veyq/mobile/bridge/Bindings.kt` |
| The words' and theme's generator flags | `grep -n 'add_argument' src/mobile/words/generate_words.py src/mobile/theme/generate_theme.py` |
| Guard rules named for Linux | `grep -n 'linux' scripts/validate_mobile_words.py scripts/validate_mobile_platform_guidelines.py scripts/repo_layout.py` |
| The launcher | `grep -rn 'linux' settings.gradle.kts launcher/linux/` |
| Docs that name Windows or mingw | `grep -rln 'Windows\|mingw\|WinUI' docs/ src/mobile/CLAUDE.md infra/README.md site/src/content/docs` |
| Pins a guard reads | `grep -n 'verification-metadata\|Cargo.lock\|packages.lock' scripts/validate_build_input_pins.py` |

## Phases

### Phase 1 — The core on Windows — done (2026-11-10)

D1's source sets; D2's mingw slices (`-mxsave` and its `config.log` check); D4's engine; `LibsodiumConformanceTest`
reaching mingw; `NormalizationTest` and `WallClockTest` in `bridgedTest`; the verification metadata for the new
target, written from an empty Gradle home; `windows/run.py test`. O1 is not needed yet: the test binary needs no .NET.

**Done when:**
- `python3 src/mobile/windows/run.py test` runs `test.exe` on the VM with **zero failures**: all 172 vector cases,
  `NormalizationTest` over every line, `WallClockTest` in the VM's W. Europe Standard Time.
- The bites are recorded: a stub `nfc` red; an AEAD off by one byte (`aWrongPrimitiveIsCaught`) red; libsodium built
  without `-mxsave` refused by the build script (its `config.log` check) — and, with that check removed, the test
  binary red at start (W5's defect, reproduced on purpose).
- `VEYQ_LIVE=http://veyq.localhost:8080 run.py test --tests '*LiveSignInTest*'` signs in on Windows over WinHttp,
  through the `-R` tunnel and O2.
- `:core:compileBridgedMainKotlinMetadata` and `:core:compileNativeMainKotlinMetadata` are green.
- Unchanged and green: `./gradlew :core:jvmTest :core:macosArm64Test`, and `python3 src/mobile/linux/run.py test`
  (Linux's sets moved).
- `/usr/bin/python3 scripts/validate_build_input_pins.py` is green.

**Met (2026-11-10, commit `73b9bc7a8`):**
- [x] `/usr/bin/python3 src/mobile/windows/run.py test` → exit 0: `176 tests from 38 test cases ran`, `PASSED 176`,
  `libsodium conformance: 172 cases`, `NormalizationTest: 20034 lines, 1094978 other code points, 0 failures`,
  `WallClockTest` OK under `windows zone: W. Europe Standard Time` (kotlinx-datetime names it `Europe/Berlin`, which
  the test asserts). The binary is x64, run on the ARM64 VM under emulation; no x64 PC measured.
- [x] Bites: a stub `nfc` (`= s`) → `NormalizationTest … 31233 failures`, exit 1; `aWrongPrimitiveIsCaught` green on
  Windows (the AEAD off by one byte is caught); `MINGW_FLAGS` without `-mxsave` → `build_libsodium.py` exits 1 naming
  `config.log` (`'__builtin_ia32_xgetbv' needs target feature xsave` in it); with the check removed as well → the
  binary red at start, both conformance tests `FAILED` with `IllegalStateException: this CPU has no AES-GCM support`
  (W5's defect, reproduced). Each restored by snapshot (`cmp` equal) and the good slice put back; the run after is green.
- [x] `VEYQ_ENV_FILE=<main checkout>/.run/.env VEYQ_LIVE=http://veyq.localhost:8080 run.py test --tests
  '*LiveSignInTest*'` → `windows (winhttp) tenants: Familie Raddatz@veyq.localhost, Turnverein@verein.localhost`, both
  personal scopes open, `PASSED 1`; the env file is gone from the VM afterwards (`dir F:\veyq\test`).
- [x] `./gradlew :core:compileBridgedMainKotlinMetadata :core:compileNativeMainKotlinMetadata
  :bridge:compileBridgedMainKotlinMetadata` green.
- [x] `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` green (344, 176 and 15 tests, 0 failures, from
  their JUnit XML); `python3 src/mobile/linux/run.py test` → `PASSED 176`, and `linux/run.py bridge` (Exports.kt moved)
  → `the library answered every step`.
- [x] `/usr/bin/python3 scripts/validate_build_input_pins.py` green.

### Phase 2 — `:bridge` on Windows, and the C# types — done (2026-11-10)

D5's DLL, export gate and smoke; D6's writer, `Veyq.Bridge` and its tests; D7's package pins and the deprecation
gate (they arrive with the first C# project); `BridgeHeaderTest` against both headers.

**Done when:**
- `./gradlew :bridge:jvmTest` is green.
- `run.py bridge`: the export table is exactly the six and `veyq_symbols`, and red with the restriction dropped (its
  bite). The smoke runs on the VM — and with `VEYQ_LIVE`, it signs in over WinHttp.
- `dotnet test` of `Veyq.Bridge.Tests` is green on the Mac and on the VM (x64). Its bite: the writer dropping one
  field turns the round trip red.
- The generated `Bridge.g.cs` is not tracked.
- `python3 src/mobile/linux/run.py build` is still green (the Rust writer is untouched).

**Met (2026-11-10, commit `06a3e6b3a`):**
- [x] `./gradlew :bridge:jvmTest` → green, 16 tests, 0 failures (JUnit XML), `BridgeHeaderTest` against
  `libveyq_api.h` and `veyq_api.h`; the C# writer's declarations held by `theGeneratedCSharpDeclaresEachFunction`.
- [x] `/usr/bin/python3 src/mobile/windows/run.py bridge` → exit 0: `objdump -p veyq.dll: 7 exports`, the smoke on the
  VM `the library answered every step`. Bite: the `.def` line removed from `bridge/build.gradle.kts` → exit 1,
  `185 exports` named beyond bridge.h's; restored by snapshot (`cmp` equal) and green again.
  `VEYQ_ENV_FILE=<main checkout>/.run/.env VEYQ_LIVE=http://veyq.localhost:8080 run.py bridge` → exit 0, `ok
  Session.SignIn is answered Done`, `ok the shell's lists arrive with the personal space` (WinHTTP through the `-R`
  tunnel); `F:\veyq\bridge` holds only `smoke.exe` and `veyq.dll` afterwards.
- [x] `/usr/bin/python3 src/mobile/windows/run.py build` → exit 0: `no package is deprecated or vulnerable`; on the Mac
  `total: 3, succeeded: 2, skipped: 1` (`CoreTest` is Windows only), on the VM (`net10.0|x64`, SDK 10.0.404) `total: 3,
  succeeded: 3`. Bites: the writer dropping `separator` → the Mac's round trip red, `The JSON property 'separator'
  could not be mapped to any .NET member contained in type 'Veyq.Bridge.Wire.NoticeArg+Joined'`; the deprecation gate
  with `WindowsAzure.Storage` 9.3.3 added → `WindowsAzure.Storage 9.3.3 (deprecated)`, `Newtonsoft.Json 10.0.2
  (vulnerable)`, red. Both restored by snapshot (`cmp` equal) and green again.
- [x] `git ls-files src/mobile | grep -E '\.g\.cs$'` is empty: `Bridge.g.cs` is under `src/mobile/build/` (ignored);
  each C# project's `bin/` and `obj/` are ignored (`src/mobile/.gitignore`).
- [x] `DOCKER_CONFIG=<scratch> python3 src/mobile/linux/run.py build` → exit 0 (`cargo test`: 7 passed), `linux/run.py
  bridge` → `the library answered every step`, `linux/run.py test` → `PASSED 176`; `./gradlew :core:jvmTest --rerun
  :core:macosArm64Test --rerun` → 344 and 176 tests, 0 failures. `--warning-mode all` after the build-script edit: no
  deprecation (only the existing disabled-test-task notices). No new dependency or target, so no verification-metadata
  write.

### Phase 3 — The Windows app's ground — done (2026-11-10)

D8's generators; the WinUI project with an empty window that starts the core and shows its version's words; the guards
and the launcher (D10); `run.py build`/`run`/`drive`; `Veyq.Drive`; the scheduled tasks; the tunnel. First
measurement: an unpackaged, self-contained WinUI build with the .NET SDK alone (`dotnet build -r win-x64`) on the
ARM64 VM — stop and ask if it needs more (O4).

**Built (Phase 3):**
- **The first measurement passed:** `dotnet build Veyq -r win-x64` on the ARM64 VM (SDK 10.0.404) built the unpackaged,
  self-contained WinUI app with the .NET SDK and the NuGet packages alone — no Visual Studio, no Windows SDK installer
  (XAML compiler, makepri and the projection all come from the packages); `bin\…\win-x64` holds `Veyq.App.exe`, `Veyq.App.pri`,
  `coreclr.dll` and the App SDK's DLLs (279 files), its `runtimeconfig.json` `includedFrameworks` .NET 10.0.15. O4 stays
  "not needed".
- **The lock file is one for both hosts:** the Mac's restore (`EnableWindowsTargeting`) wrote `Veyq/packages.lock.json`
  and the VM's `--locked-mode` restore accepted it. Neither the runtime packs nor `Microsoft.Windows.SDK.NET.Ref` are in
  it (they are SDK-chosen downloads, not package references), so the self-contained output is the building SDK's
  runtime — the VM's, 10.0.15. What `.WinUI` pulls beside the three references: `.Base`, `.InteractiveExperiences`
  (2.6.5), `Microsoft.Web.WebView2` 1.0.3719.77, `Microsoft.Windows.SDK.BuildTools.MSIX` 1.7.251221100; all pass the gate.
- **Words (D8):** `.resw` resource names take `#` (`memory_items#one` reads back through MRT Core). Which language's plural
  rule applies is said by the catalog itself: each `.resw` carries `_language` (no key can be named so: keys start with a
  letter), so a German catalog's forms are never chosen by another language's rule. An untranslated key (a name) is
  written into every language's catalog. `Words.g.cs` has an English fallback per key, as gettext's `msgid`; the test
  asserts German for that reason. The app's `.pri` is `Veyq.App.pri` (the assembly's name), read by MRT Core's
  `ResourceManager()` beside the exe; `Veyq.Tests` opens the same file by path (`VEYQ_PRI`).
- **Sizes have one culture, the user's:** `StrFormatByteSizeEx` ignores `SetThreadUILanguage` and `SetThreadLocale`,
  before and after its first call (measured on the VM: `1,50 KB` and `12 Bytes` under every switch), so `SaidTest`
  asserts a size in the user's culture (de-DE on the VM); money and days are asserted in de-DE and en-US.
- **Veyq.Tests** compiles the app's `Words.cs`, `MrtWords.cs`, `Said.cs` and the generated `Words.g.cs` rather than
  referencing the WinUI exe; it is an x64 MSTest executable like `Veyq.Bridge.Tests`.
- **The window** shows the app's name and the core's state in words (`status`: `Opening…`, then the session's gate —
  `Sign in` at `SignIn`); `Log.cs` writes the gates by kind. `CreateAction.cs` exists already (the guard's block), used
  by nothing until Phase 4.
- **Veyq.Drive** is one tool for both tasks: `--launch <file>` (reads the app's exe and environment from a file, deletes
  it, starts the app: the start wrapper D9 names, so a secret never reaches a command line) and `--drive <file>`
  (the steps). `net10.0-windows` with `UseWPF` (UIA client and the PNG encoder), framework-dependent, ARM64-native on the
  VM; a `WinExe`, so no console window covers the app. `shot` copies the screen at the window's DWM bounds.
- **The scheduled tasks were refused to this session's agent** (its auto-mode classifier: "Unauthorized Persistence"),
  so `run.py register` holds the registration for the owner to run (O5, as D9 foresaw). Without them nothing reaches
  session 1: the app started from ssh (session 0) crashed at once, `0xC000027B` in `combase.dll`, WER's `P7` `80070005`
  (access denied; Application log events 1000/1001) — no evidence about the app either way.
- **The app's assembly is `Veyq.App`, never `veyq`** (found at the first run, 2026-11-10): a .NET app's own assembly is
  `<AssemblyName>.dll` beside its exe, and as `veyq` it overwrote the core's native `veyq.dll` in the build output (310 KB
  managed where 23.9 MB native belonged). The app then found no `veyq_init` (`EntryPointNotFoundException`), and that
  exception, out of the `async void` start, ended it in `combase.dll` (`0xC000027B`, WER `P7` `80131523`) with nothing
  in its log. `Veyq.csproj` says so at the name; `run.py` (`APP_NAME`) and `Veyq.Drive` follow it; the window now logs a
  core DLL that does not load instead of dying.
- **`register` names the account as Windows does:** over ssh `$env:USERDOMAIN` reads `WORKGROUP`, which maps to no SID
  (`Register-ScheduledTask`: `0x80070534`); `[Security.Principal.WindowsIdentity]::GetCurrent().Name` is
  `win-…\kevin`.
- **Guards (D10):** windows-literal also covers `Label`, `Description` and `ToolTipService.ToolTip` (an `AppBarButton`'s
  words are its `Label`) and a literal handed to `CreateAction.Button`/`.Menu`; C# is read by the Kotlin lexer.

**Done when:**
- `run.py build` is green: the round trip; the words — every key German and English, a plural on both sides of 1,
  asserted in German, which differs from the fallback; money, days and sizes in two cultures; the deprecation gate.
- `run.py run` opens the window in the owner's session, and `run.py drive tree` returns its UIA tree as text on the
  Mac, naming the core's started state. One `shot` comes back as the phase's proof.
- Both guards are green with every new rule bitten; `validate_guard_gaps.py` has run once.
- None of `.resw`, `Words.g.cs`, `Theme.xaml` or `Bridge.g.cs` is tracked: `git ls-files src/mobile/windows | grep -E
  '\.resw$|\.g\.cs$|Theme\.xaml$'` is empty.

**Met (2026-11-10):**
- [x] `/usr/bin/python3 src/mobile/windows/run.py build` → exit 0: `no package is deprecated or vulnerable` for each of
  the four projects; the Mac's `Veyq.Bridge.Tests` `total: 3, succeeded: 2` (`CoreTest` Windows only); on the VM the app
  and Veyq.Drive built, `Veyq.Bridge.Tests` `total: 3, succeeded: 3`, `Veyq.Tests` `total: 6, succeeded: 6` —
  `EveryKeySaysItsWordsInEnglishAndGerman` (every key, plurals at 1, 2 and 0, through the app's `.pri`; more than half
  the German words differ from the English fallback, asserted), `APluralTakesItsFormOnBothSidesOfOneInGerman`
  (`1 Objekt`, `2 Objekte`), money in de-DE and en-US (`1.234,56 €`, `€1,234.56`, a foreign `¥` in de-DE), days in both,
  sizes in the VM's de-DE (`1,50 KB`; one culture only, see Built). Bite: the generator writing the English into the
  German catalog → `drawer_open in de, n = 0`, expected `Menü öffnen`, actual `Open menu`, `failed: 1`, exit 2; restored
  by snapshot (`cmp` equal) and green again.
- [x] `run.py register` (the owner's go in chat, 2026-11-10: "Darfst du machen") → `veyq-app Ready`, `veyq-drive
  Ready`. `run.py run` (background, the tunnel held) → `the app runs in the logged-on session`; the log `the core
  started`, `session Starting`, `session SignIn`. `run.py drive tree "shot ground.png"` → `Window "veyq"`, `Text "veyq"
  #heading`, `Text "Anmelden" #status`, the title bar's German buttons; the shot shows the Mica window with "veyq /
  Anmelden" (`src/mobile/build/windows/shots/ground.png`). Starting a registered task (`schtasks /Run`) passed.
- [x] `validate_mobile_words.py` and `validate_mobile_platform_guidelines.py` green; each new rule bitten red and green
  on the restore (snapshot, `cmp` equal, `git status --porcelain` unchanged): windows-literal (a C# `Text = "Hello
  world"`, a `$"Open {1} now"` to `AutomationProperties.SetName`, a XAML `Text="Opening the app"`, a
  `<TextBlock>Opening</TextBlock>`), windows-resw (a `Strings/de-DE/Resources.resw`), windows-block (`Symbol.Add` in
  `MainWindow.xaml.cs`, `Icon="Add"` in XAML, `CreateAction.cs` without `Symbol.Add` and `MenuFlyout`: 2 violations),
  windows-floating-button (a XAML `Button VerticalAlignment="Bottom"`, a C# variable given it, an initializer with it).
  `validate_guard_gaps.py --force` ran once: `guard gaps OK: 104 pinned case(s) hold`.
- [x] `git ls-files src/mobile/windows | grep -E '\.resw$|\.g\.cs$|Theme\.xaml$'` → empty (exit 1).
- Launcher: `./gradlew -q windows:tasks --group veyq` lists `bridge`, `build`, `coreTest`, `run`; `--warning-mode all`
  printed no deprecation. `validate_build_input_pins.py` green.

### Phase 4 — The slice: sign-in, the shell, Home, a Pot, Search — done (2026-11-11)

Each screen draws the core's state and calls its actions; nothing else (a rule found here moves into the core).

**Done when** a pass on the VM against the dev instance has run:
- **The instance:** `./gradlew backend:seed`; the second tenant from `DevSecondTenantTest`; the seeded Pots.
- **The steps, driven by `run.py drive`:** sign in with `--devlogin`, switch tenant and space, Home filtered, a folder,
  a Favorite, a Pot's entries, overview and people, Search and its hit opened.
- **The forms:** maximized (Left), 800 wide (LeftCompact), 500 wide (LeftMinimal). German (the VM's own) and English
  (`VEYQ_LANGUAGE=en-US`), light and dark (`VEYQ_THEME`).
- **The Pot's numbers** match the core's live read (`LiveBridgeTest` with `VEYQ_LIVE`: Amsterdam's 5 entries, Anna owed
  67.34, Ben owing it), and the web's Pot page if its dev server runs.
- **Logs first:** the app's log is read before its tree, and holds no exception.
- **Proof:** a UIA tree per step; a screenshot per form.
- `run.py build` and both guards are still green.

**Built (Phase 4):**
- **The screens** are code, not XAML (`SignInView.cs`, `ShellView.cs`, `HomeView.cs`, `PotPage.cs`; `MainWindow.xaml`
  keeps only the opening face and a `ContentControl` the other two take turns in): each draws the core's state and
  calls its actions through `Act.Send` (Linux's `bridge::send`: the answer is logged by its kind, never awaited by the
  screen). `SaidNotice.cs` writes a `Notice`, a `Piece` and a line through the generated `Words.Say` (the typed
  arguments first, then every argument as its text, as `said.rs` does). `DevLogin.cs` is `#if DEBUG` only and takes the
  four `VEYQ_DEV_*` once, removing them from the process's environment; `run.py run --devlogin` puts them in the launch
  file Veyq.Drive deletes on reading, never on a command line. No rule moved to the core: none was found.
- **Settings at the pane's foot is a `FooterMenuItems` item, not `IsSettingsVisible`'s**: the built-in item says
  WinUI's own word in the system's language, and every word comes from `words.json`. WinUI's own chrome (the pane's
  button, back, the scroll bar) still speaks the system's language under `VEYQ_LANGUAGE=en-US` (`"Navigation öffnen"`
  in the English pass's tree) — the system's, as on every Windows app.
- **A pane item is chosen by its selection**, not by `ItemInvoked`: a `NavigationViewItem` has no UIA Invoke (`invoke
  home` → `can be neither invoked, toggled nor expanded`), only SelectionItem. `SelectionChanged` sends the choice
  unless the state is drawing it; `ItemInvoked` acts only for the item already selected (Home again, out of a folder),
  which no selection change reports.
- **Home is one list per group under its heading** (Explorer Home's sections), not one grouped `ListView`: a grouped
  one needs a `CollectionViewSource` and a header `DataTemplate` bound to C# objects; per-group lists needed neither.
- **The switcher's menu** is the spaces, then each server's tenants (`RadioMenuFlyoutItem`s, separators between them,
  the server's label as a tenant's tooltip — a `MenuFlyout` has no section heading), then Manage servers disabled.
- **A Search hit arrives as its row's content**: chosen by a click (UIA Invoke on the suggestion), `QuerySubmitted`'s
  `ChosenSuggestion` is the row's `StackPanel`, not the `ListViewItem` (log: `a chosen hit reached no hit
  (StackPanel)`); both carry the hit as their `Tag`. A chosen hit clears the query; hits drawn anew under a standing
  query open no list unless the box has focus (the list re-opened over a space switch).
- **LeftMinimal lays back and the pane's button over the content's top left**: the title's row takes a left inset
  there (`DisplayModeChanged`), else "Amsterdam" ran under them (`shot de-light-500.png`, first pass).
- **A width-capped column straight in a `ScrollViewer` sits off centre and clips** (measured: the Pot's amounts were cut
  off at the right edge, the sign-in column at x 335 of 784): the lists fill the width; the sign-in's column is capped
  inside a stretching `Grid`.
- **`VEYQ_THEME` sets the application's theme, not only the window's**: a brush looked up in code
  (`TextFillColorSecondaryBrush` for a row's line) is the application theme's, so under the window's dark alone the
  captions stayed light's — dark on dark (`shot en-dark-500-pane.png`, first pass).
- **Money follows the system's regional format, words `VEYQ_LANGUAGE`**: the English pass writes `67,34 €`, the VM's de-DE
  format, as a person's English Windows with German regional settings would (D8's `CurrencyFormatter(code)`).
- **Veyq.Drive** finds an element in any top-level window of the app (a flyout, a menu, a suggestion list is its own
  `PopupHost` window) and `tree` prints them after the main one; `invoke`/`select` take the rest of the line as the id
  (a name's spaces). AutomationIds name what they open: `home`, `app:EXPENSES`, `favorite:<name>`, `recent:<name>`,
  `folder:<name>`, `object:<name>`, `filter:<APP|all>`, `place:<tab key>`, `space:<name>`, `tenant:<name>`,
  `hit:<name>`, `switcher`, `settings`, `new`.
- The VM's display is now 1290 × 848 (the maximized window's tree), not W7's 1024 × 768; the three forms were reached
  by `maximize`, `size 800 700` and `size 500 700`.

**Met (2026-11-11, commits `f42dc8f74`, `88a6fb79d`):**
- [x] The instance: `VEYQ_ENV_FILE=<main checkout>/.run/.env VEYQ_LIVE=http://veyq.localhost:8080 ./gradlew
  :bridge:jvmTest --tests '*LiveBridgeTest*' --rerun` → `servers: veyq.localhost=Familie Raddatz/Turnverein`,
  `Home: home_group_folders=6, home_group_contents=8`, `Amsterdam: 5 entries, balances "Anna"=6734, "Ben"=-6734`:
  seeded, the second tenant there; nothing was seeded anew.
- [x] The steps, driven by `run.py drive` against `VEYQ_LANGUAGE=de-DE VEYQ_THEME=light run.py run --devlogin`, one
  drive, `FAILED` nowhere, a tree after each step: the log `signing in as the dev principal (debug build)`, `session
  Signed`; the switcher's menu (`space:Familie Raddatz`, `space:Persönlich`, `tenant:Familie Raddatz`,
  `tenant:Turnverein`, `switcher-manage disabled`); space `Persönlich` → `#title "Persönlich"`, subtitle `Familie Raddatz
  · veyq.localhost`; tenant `Turnverein` → `#title "Turnverein"`, `favorite-none`, and back to Familie Raddatz;
  `filter:EXPENSES selected`; `folder:Reisen` → `#title "Reisen"`, back enabled, and back; `favorite:Sri Lanka` → its
  Pot at the top (back disabled); `object:Amsterdam` → five `#entry:` rows, `place:expenses.overview`,
  `place:expenses.settings`; `ctrl+e`, `set TextBox Ams` → `hit:Amsterdam`, invoked → `#title "Amsterdam"`, the query
  cleared.
- [x] The forms: maximized → `TogglePaneButton "Navigation schließen"`, `PaneRoot [0,23 320x825]` (Left); 800 →
  `"Navigation öffnen"`, the pane 48 wide with icons (LeftCompact); 500 → the pane closed, its button over the content
  (LeftMinimal), `invoke TogglePaneButton` opens it over the content. German light (the pass above) and English dark
  (`VEYQ_LANGUAGE=en-US VEYQ_THEME=dark`: `"Switch space, tenant or server"`, `Balances`, `Ben pays Anna`, `Total`) in all
  three forms; the sign-in without `--devlogin` (German, light, 800).
- [x] The Pot's numbers against `LiveBridgeTest`: Amsterdam's overview says `Anna, bezahlt 144,69 € · verbraucht 77,35 €,
  bekommt 67,34 €`, `Ben, bezahlt 9,99 € · verbraucht 77,33 €, schuldet 67,34 €`, `Ben zahlt an Anna, 67,34 €`, `Summe,
  154,68 €`; its entries are five; its people `Anna, 67,34 €`, `Ben, -67,34 €` — the live read's 6734 and −6734. The
  web's Pot page was not compared (its dev server was not part of this pass).
- [x] Logs first: each drive printed the app's log before the tree; it holds the gates and the devlogin line, no
  exception and no `answered`/`did not` line in the final passes.
- [x] Proof: a UIA tree per step (the drive's output); a screenshot per form, under `src/mobile/build/windows/shots/`:
  `de-light-signin.png`, `de-light-max-home.png`, `de-light-max-pot.png`, `de-light-800.png`, `de-light-500.png`,
  `en-dark-max-home.png`, `en-dark-max-pot.png`, `en-dark-800.png`, `en-dark-500.png`, `en-dark-500-pane.png`.
- [x] `/usr/bin/python3 src/mobile/windows/run.py build` → exit 0: `no package is deprecated or vulnerable` (four
  projects); the Mac `total: 3, succeeded: 2`; the VM `Veyq.Bridge.Tests` `succeeded: 3`, `Veyq.Tests` `succeeded: 6`.
  `validate_mobile_words.py` and `validate_mobile_platform_guidelines.py` green; no guard rule was added. The
  windows-literal rule reads the new screens: `Header = "Server address"` in `SignInView.cs` → red at line 29, restored
  by snapshot (`cmp` equal), green.

### Phase 5 — The docs

- ADR 0127: its **Built** line (decision 5 built for this slice; libsodium as decided, W5's `-mxsave` in the
  Consequences).
- ADR 0123's Consequences: the core on Windows, the bridge's Windows half, the Windows app, each with what was
  measured.
- [native-apps](../docs/crosscutting/native-apps.md): the Windows row (builds, UI, layout, seen running — the ARM64
  VM under x64 emulation); Open: device trust, MSIX, pickers, drag and drop, an x64 PC never run; and the refusal
  row beside the Pi 4's — no AVX (an x64 CPU without it, a Windows-on-ARM PC whose Prism hides it).
- `src/mobile/CLAUDE.md`: `windows/` in the layout, `run.py`'s verbs in Prove your work, and the traps — libsodium's
  `-mxsave` on mingw, a cinterop that keeps an old static library, `*.localhost` on Windows, session 0, the export table, `llvm-ar s`, CP850 and `-EncodedCommand`.
- `infra/README.md` § The launcher.
- The published site stays as it is: no Windows app ships to users in this CR.

**Done when:** the trust-chain guards are green (`/usr/bin/python3 scripts/validate_*.py` as the `trust-chain` job
runs them, `validate_changes.py` among them), and every "measured" in the touched docs names its command.

## Not in this CR (the next one, for Linux and Windows together)

- **Device trust:** DPAPI with Windows Hello, reached by the core itself through cinterop — the key never crosses the
  bridge (ADR 0127's Consequences), as libsecret on Linux.
- **Packaging:** MSIX with its signing and its PNG assets, and the app's icon.
- **The system's file pickers, save dialogs and drag and drop.**
- Every other application's screens, uploads, a Pot's writes; a CI job for the apps; a run on a real x64 PC; and
  Windows ARM64 native, which waits for Kotlin/Native.

## Owner questions

- **Q1 — AES-256-GCM on Windows (settled, owner 2026-11-10): libsodium alone.** The owner leaned to one source; the
  red that had made CNG look necessary was a defect of our libsodium build, not Prism's (W5: Prism shows AVX by
  default; `configure` never read XCR0 without `-mxsave`). With the flag every case passes under Prism. D3 records it.
- **Q2 — The VM steps O1–O3 and O5 (settled, owner 2026-11-10: approved).** O1–O3 done the same day (§ Owner
  steps). The owner also updated the VM to Windows 11 26H2 (26300.9711).
- **Q3 — none left for Linux.** Linux x86-64 has the same AVX condition (libsodium's rule is the same), but there
  `configure` assembles XGETBV itself (`HAVE_AVX_ASM`; its refusal is for Windows x64 only), so the defect W5 found
  does not arise — measured: `./configure --host=x86_64-unknown-linux-gnu` with `build_libsodium.py`'s flags says
  `checking if _xgetbv() is available... no` and `checking whether we can assemble AVX opcodes... yes`. An x86-64 Linux PC without AVX is refused as the Pi 4 is; the docs phase puts both refusals in one
  row. Linux x86-64 has still never been run.
- **Q4 — Registering the two scheduled tasks (settled, owner 2026-11-10: "Darfst du machen"; registered the same day, and the owner declared both test VMs disposable — anything may be done there).** The implementing agent's auto-mode classifier refused
  `Register-ScheduledTask` over ssh ("Unauthorized Persistence"), as D9 foresaw. Recommendation: the owner runs
  `python3 src/mobile/windows/run.py register` once from this Mac (it registers `veyq-app` and `veyq-drive`, interactive
  only, as `kevin`, on demand, each running `F:\veyq\drive\Veyq.Drive.exe`; `run.py build` has put that exe there), then
  Phase 3's last Done-when is `run.py run` (in the background: it holds the tunnel) and `run.py drive tree "shot
  ground.png"`, before Phase 4 starts. Alternatively the owner adds a permission rule for that one command. Running
  an existing task (`schtasks /Run`) is what every later `run`/`drive` does; whether the classifier passes that too is
  unmeasured.

## Retrospective

### Phase 1 (2026-11-10)

- **The refine's measurements carried the phase.** W1–W5 had already found every trap (`llvm-ar s`, `-mxsave`, the
  stale klib), so the build was one pass. What reality still changed was small and is recorded under D1/D2: the DSL's
  exact form, `config.log`'s two-line check, and that the live test's engine is a test `expect`, which D1 had named
  only for `:bridge`.
- **A Gradle deprecation slipped in and was caught only by reading `--warning-mode all`.** `val bridgedMain by
  getting` is deprecated for Gradle 10; the build only said "deprecated features were used". Next time: run
  `--warning-mode all` once after any build-script edit, before the phase's verification, not after.
- **Process:** the empty-Gradle-home write failed first on the wrapper's own download (a read timeout), not on a
  dependency; seeding the empty home with the wrapper distribution alone keeps the write honest. Worth one line in
  `src/mobile/CLAUDE.md`'s trap with Phase 5.

### Phase 2 (2026-11-10)

- **The CR named the means and the measurement chose among them in one link each.** D5 listed `*_STATIC`, lld's
  `--exclude-all-symbols` and K/N's `.def`; three links (`objdump -p` after each) showed the first already done, the
  second wrong (it removes the six too: they were auto-exported like everything else) and the third enough alone. Worth
  keeping: a CR that lists candidate means rather than picking one is cheap to implement when each is one measurement.
- **Two C# naming traps the descriptor walk could not foresee were found only by compiling the generated file**: the
  source generator's simple-name metadata (SYSLIB1031) and its unescaped `@`-keywords (CS1525), and a third one
  (CS0542) avoided by keeping the serial names. The Rust writer had met its own (`r#ref`, `Box`); a third host language
  will meet its own too — generate, compile, then read the errors, rather than reason about the names first.
- **The lock file is the SDK's as well as the packages'.** An innocent analyzer property (`IsAotCompatible`) pulled an
  SDK-versioned package and the second host's locked restore went red. The first VM run caught it; the Mac alone never
  would have. Next time: the first locked restore of a new C# project runs on both hosts before anything else is built
  on it.
- **Process:** `dotnet package list` restores by itself, so the deprecation gate's first bite failed for the wrong
  reason (NuGet's audit refused the restore before the gate looked). The bite was what showed it — the gate now reads
  the locked graph with `--no-restore`. And the VM's dotnet writes UTF-8 where Phase 1's `run.py` reads CP850:
  English words (`DOTNET_CLI_UI_LANGUAGE`) avoid the question for dotnet's own output.

### Phase 3 (2026-11-10)

- **The phase's riskiest unknown was the cheapest to settle.** The CR made "does WinUI build with the SDK alone" Phase
  3's first measurement and a stop-and-ask; a bare project answered it in one push (20 s build on the VM), so everything
  after was ordinary work. Worth keeping: put the question that could stop a phase first, with a five-file probe.
- **The persistence step was foreseeable as a refusal and was.** The brief and D9 had already named the fallback (the
  owner registers), so the refusal cost one command, not a redesign — but it leaves the phase's visible proof undone.
  Next time a CR needs a standing change on a host (a task, a service, a rule), make it an owner step done *before* the
  implementing session, as O1–O3 were, rather than a step the agent tries.
- **One tool, two modes, beat two tools.** D9 sketched a start wrapper and a driver; the wrapper became Veyq.Drive's
  `--launch` mode, which removed a PowerShell execution-policy question and a batch file's quoting of secrets. A CR that
  names a component's job rather than its form leaves room for that.
- **A formatter's culture is not always switchable** (`StrFormatByteSizeEx`): "tested in two cultures" was written
  from the other two formatters' shape. The probe took two VM calls; the CR now says sizes have one culture.
- **Process:** a XAML comment may not contain `--` (WMC9997): a comment naming a command-line flag broke the first
  app build. And generated C# needs its own `#nullable enable` (CS8669), as Bridge.g.cs already had — the second
  generator to meet it; a third should copy the header of the first.

### Phase 4 (2026-11-11)

- **The driver found the app's accessibility gaps before a person would.** A pane item without Invoke, a chosen hit
  that arrives as its row's content, popups that are windows of their own: each surfaced as a red step or a log line,
  never by looking. Worth keeping: give every interactive control an AutomationId that names what it opens, and drive
  by it from the first screen on — the ids made the pass's steps readable as the CR's Done-when.
- **Two layout defects were only visible in a screenshot, never in the tree**: a width-capped column in a `ScrollViewer`
  (the tree's bounds were plausible; the amounts were clipped) and code-looked-up brushes under the window's theme only
  (the text was there, dark on dark). The owner's rule — text first, screenshots as proof — held, but the proof shots
  are where these two were found: take each form's shot early in the phase, not only at its end.
- **The app's state outlives its process**: the core reopened the last object at start, so a pass's first step
  assumed Home and failed (`no element with AutomationId object:Amsterdam`). A pass starts from a state it sets
  (`select home`), not one it remembers.
- **Process:** one app build plus a launch on the VM took about 70 s, so the loop was build, launch, drive the few
  steps in question, read the log; a scratch script ran only the VM's build to iterate on compile errors. `cp` is
  interactive in this shell (`command cp -f`).
