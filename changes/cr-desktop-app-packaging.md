---
id: cr-desktop-app-packaging
title: The desktop apps ship — Linux as a Flatpak bundle, Windows as an MSIX, built in Checks and attached to the release
status: planned
satisfies: []            # no use case: packaging adds no user operation (Definition of done)
adrs: ["0057", "0091", "0096", "0122", "0123", "0129", "0130"]
invariants:
  - "operator-blind E2EE: a package changes where the app runs, never what it sends; no package, manifest or metadata carries anything of an account, and the precise release reaches no wire (ADR 0057 decision 4: the package's metadata names its version, the app's code does not)"
  - "self-hostable by default: every package is built from this repository by one command per platform with pinned inputs, and a release carries it as a file anyone can install and verify without a store, a Flathub account or any veyq-run host; a store or Flathub listing is optional on top (owner questions 4, 5)"
  - "a release is a promoted snapshot, nothing is built again (ADR 0091 decision 5): the packages a release attaches are the bytes a Checks push run built and tested; the MSIX signature added at promotion changes no payload byte, proved by its block map"
  - "no secret in the tree: no certificate key, no password, no PFX is committed; a signer enters through the environment of the one job that signs"
  - "no deprecated, vendor-locked or closed build dependency (root CLAUDE.md): each tool added is checked and recorded where it is added; a pre-1.0 tool is not added where a pinned one already does the job"
  - "the apps behave as before: both desktops' `run.py build` and `./gradlew :core:jvmTest :core:macosArm64Test :bridge:jvmTest` stay green in every phase"
---

## Goal

The Linux app (GTK 4 / libadwaita in Rust, `src/mobile/linux`) and the Windows app (WinUI 3 in C#,
`src/mobile/windows`) run today only from a developer's build: Linux in a Fedora 44 container under Broadway or in the
test VM's session, Windows unpackaged and copied onto the VM. Neither can be handed to a person. This CR makes each an
installable package — **a Flatpak bundle** (`.flatpak`, x86_64 and aarch64) on the GNOME runtime and **an MSIX**
(x64, which Windows on ARM runs under emulation) — built reproducibly from the repository, built and smoke-run in every
Checks push run, staged with the images, signed with them at snapshot and release, and attached to the GitHub Release
beside the server's recipe, listed in the signed manifest. The site gets the page that tells a person how to install,
update and verify the app. Android and the Apple app are not packaged here (inbox #884).

## Context & constraints

- **Read first:** ADR 0129 decisions 4 and 5 (each desktop's stack; Linux "built and tested against the runtime it ships
  on"), ADR 0123's Consequences (the Linux app, the Windows app: what each pass measured), ADR 0130 decision 3 (a file
  by path — whether a sandboxed or packaged app's picked path opens in the core is this CR's to measure), ADR 0057
  decisions 4 and 6, ADR 0091 decision 5, ADR 0096 decision 3 (what a release carries),
  [native-apps](../docs/crosscutting/native-apps.md) § Platforms and § Open (the Flatpak and MSIX bullets are this
  CR's inventory of what Depot and Expenses learned), [release-and-deployment](../docs/crosscutting/release-and-deployment.md)
  (§ The pipeline, § Publishing, § Releasing, § What a release consists of), `src/mobile/CLAUDE.md` (§ Prove your work,
  § Traps), `changes/cr-core-qr-decoder.md` (D4: the Linux camera through the Camera portal, Windows' through
  `MediaCapture`).
- **The governing decisions are this CR's, not yet an ADR.** The refine was scoped to writing this CR alone, so the ADR
  that records D1–D11 is phase 1's first commit, before any build file. Every later phase cites that ADR, not this file.
- **Substrate:** branch `claude/windows-cr-f6c73c` at `e5f678e56`, tree clean; no file under `src/mobile/linux`,
  `src/mobile/windows` or `.github/` names Flatpak or MSIX beyond comments and the generated icon's
  (`git grep -li "flatpak\|msix" -- src .github scripts` → `.gitignore`, `linux/Containerfile`, three `linux/src/*.rs`,
  `theme/generate_theme.py`, `Veyq.csproj`, two `packages.lock.json`; read 2026-11-13).
- **The test machines, read 2026-11-13 (nothing changed there):**
  - Fedora VM `kevin@192.168.64.9` (key `~/.ssh/veyq_vms_ed25519`): Fedora 45 aarch64, GNOME Shell 51.0, Flatpak 1.18.4,
    remotes `fedora` (OCI) and `flathub` (Fedora's filtered one), **no runtime installed, no flatpak-builder**
    (Fedora 45 offers 1.4.12); `xdg-desktop-portal` 1.22.1 with `-gnome` 51.0; the Secret portal is **oo7-portal**
    (`/usr/share/xdg-desktop-portal/gnome-portals.conf`: `org.freedesktop.impl.portal.Secret=oo7-portal;gnome-keyring;`);
    libsecret 0.21.8; pipewire 1.6.9. Measured with `ssh … 'flatpak --version; flatpak remotes; rpm -q …; cat
    /usr/share/xdg-desktop-portal/*portals.conf'`.
  - Flathub, read from that VM (`flatpak remote-info [--show-metadata] flathub org.gnome.Platform//<b> --arch=<a>`):
    `org.gnome.Platform`/`Sdk` 49, 50 and 51 for aarch64 and x86_64; **51** is the GNOME 51.1 build of 2026-11-10
    (aarch64 commit `90c98497…`, x86_64 `2ec125d2…`), its runtime declares the extension
    `org.freedesktop.Platform.codecs-extra` at `26.08-extra` **without** `no-autodownload` (so it arrives with the
    runtime) and puts it on `GST_PLUGIN_SYSTEM_PATH`; `org.freedesktop.Sdk.Extension.rust-stable` `26.08` (1.98.0);
    `org.flatpak.Builder` (2026-11-04). Installed size of the runtime 1.2 GB (aarch64).
  - Windows VM `kevin@192.168.64.8`: Windows 11 **Home** 26H2 (build 26300.9974) ARM64; .NET SDK 10.0.404, the x64
    runtime 10.0.15; **no Windows SDK installed** — `makeappx.exe` and `signtool.exe` (x64 and arm64) come from the NuGet
    package `Microsoft.Windows.SDK.BuildTools` 10.0.28000.2957 the app already restores, and
    `Microsoft.Windows.SDK.BuildTools.MSIX` 1.7.251221100 is already in `Veyq/packages.lock.json` (transitive, through
    `Microsoft.WindowsAppSDK.Base`); `Microsoft.WebpImageExtension` 1.2.31.0 and the VP9, Web Media, AV1, HEVC, HEIF and
    Raw extensions are **provisioned** in the image (`Get-AppxProvisionedPackage -Online`), and the WebP one is
    **not a framework package** (`IsFramework: False`, `SignatureKind: Store`); no `AppModelUnlock` values set (the
    sideloading default). Measured by a PowerShell probe over `ssh` (`-EncodedCommand`, as `run.py` sends).
  - The VM runs with **UAC off** (native-apps § Open, the drop). A packaged app refusing to start with UAC off is
    remembered, not measured: phase 4 measures it first.
- **Tools, read 2026-11-13** (`gh api repos/<r>` and `…/releases`): flatpak-builder 1.4.12 (2026-09-15, LGPL-2.1, active);
  Flatpak 1.18.4 stable; ORAS v1.3.6 (2026-10-24, Apache-2.0, CNCF) and `oras-project/setup-oras` v2.0.3;
  `microsoft/WinAppCli` v0.8.0 (2026-11-07, MIT, **pre-1.0**); `flathub-infra/flatpak-github-actions` **archived**;
  Windows App SDK 2.6.5 (the app's pin) current stable. The repository is public, so GitHub's hosted macOS, Windows and
  arm64 Linux runners are available to it.
- **What builds where today:** everything Kotlin (the three bridge libraries `libveyq.so` ×2 and `veyq.dll`) is
  cross-compiled on the developer's Mac; the theme's Windows icon and every Apple icon are rendered by macOS Quick Look
  (`grep -n qlmanage src/mobile/theme/generate_theme.py`); the Windows app has only ever been built `-c Debug`
  (`grep -n "BUILD_APP\|-c Release" src/mobile/windows/run.py`); the Linux app only `cargo build` (debug) and for aarch64.
  No workflow builds anything under `src/mobile/` (`grep -n mobile .github/workflows/*.yml` → nothing).
- **The release side:** a release attaches `<out>/<tag>/` whole and the manifest's `assets` are what `veyqctl` downloads
  into an instance (`scripts/release_assets.py`'s docstring; `src/tools/veyqctl.py` `parse_manifest`); the asset set's
  hash is what the lanes attest and the promotion recomputes. App packages are none of that.

## Decisions

**D1 — Formats and architectures.** Linux: a single-file Flatpak bundle per architecture,
`veyq-<major>.<minor>-x86_64.flatpak` and `…-aarch64.flatpak`. Windows: one MSIX, `veyq-<major>.<minor>-x64.msix`;
no arm64 MSIX and no `.msixbundle`, because Kotlin/Native has no Windows ARM64 target (ADR 0129 decision 5), and the x64
app runs on the ARM VM under Prism (measured, native-apps § Platforms). The x86_64 Flatpak is the first x86-64 build of
the Linux app at all (native-apps § Open): CI's smoke on an x86-64 runner is its first run, a real x86-64 desktop is not
in this CR.

**D2 — Linux runtime: `org.gnome.Platform//51`, its SDK and `rust-stable//26.08`, pinned by commit.** 51 is the newest
branch (longest supported), the test VM's own GNOME, and carries GTK and libadwaita at or above what the app is written
against (`Cargo.toml`: features `v4_20`, `v1_9`); a newer library under older feature flags is the gtk-rs norm. The
manifest names the branch (`runtime-version: '51'`), and a pin file beside it names, per architecture, the commit of
the Platform, the Sdk and the Rust extension a build installs (`flatpak update --commit=`); the guard of D10 holds the
two together. A person's machine runs whatever commit of branch 51 Flathub serves (the runtime's ABI is stable within a
branch); moving to 52 is a pin bump, done when 51 nears its end of life.

**D3 — What the sandbox gets (finish-args), least first.** `--share=network` (the API and the store's URLs);
`--socket=wayland`, `--socket=fallback-x11`, `--share=ipc`, `--device=dri` (GTK's drawing); `--socket=pulseaudio` (a
medium's sound through GStreamer's sink; PipeWire answers it). **No `--filesystem`**: files come and go through the
FileChooser and document portals, which GTK uses by itself in a sandbox; a drop from Files arrives as a document-portal
path. **The secure store:** libsecret inside a sandbox talks to the **Secret portal** (a per-app keyring under
`~/.var/app/<id>/data/keyrings`, opened by a secret the host's keyring holds — on the VM oo7-portal); the alternative is
`--talk-name=org.freedesktop.secrets` with `SECRET_BACKEND=service`, i.e. the host collection as the container uses it
today. Phase 2 measures which of `LinuxDeviceKeys`' calls each answers (both on the VM) and keeps the portal unless one
of those calls fails there; the outcome and the level the device offers go into
[security-model](../docs/crosscutting/security-model.md) § What a trusted device rests on. **The camera** adds nothing
here: the Camera portal hands the app a PipeWire fd (`cr-core-qr-decoder` D4); whichever of the two CRs lands second
proves the scanner in the installed Flatpak. No `--talk-name` beyond the one above, no `--device=all`, no
`--filesystem=home`, no `--socket=x11` alone.

**D4 — Where `libveyq.so` and the generated sources are built: outside the sandbox, as today, and handed in.**
flatpak-builder builds offline in the SDK; Kotlin/Native's Gradle build downloads its distribution and toolchains and
links against its own old sysroot, never a system compiler (`src/mobile/CLAUDE.md` § Traps, the toolchain), so it
cannot run inside the SDK without network and gains nothing there. The release libraries
(`:bridge:linkReleaseSharedLinuxX64`/`…LinuxArm64`), the bridge's generated Rust, the words (`--gettext`) and the theme
(`--gtk`) are produced first and enter the manifest as one `type: dir` source; the Rust crates enter as `cargo vendor
--locked` output (cargo's own command, checked against `Cargo.lock`'s checksums; no generator tool added). The app module
then runs, inside `org.gnome.Sdk//51` with `rust-stable`, `cargo clippy --locked -- -D warnings`, `cargo test --locked`
and `cargo build --release --locked`: that is ADR 0129 decision 4's "built and tested against the runtime it ships
on". `run.py run`'s Broadway passes and `run.py test`'s core binary stay in the Fedora 44 container (a pass's
environment, not the proof of the app); `run.py build` gains the SDK as its proving ground once the Flatpak builds
(phase 3), the Fedora build kept as a quick local loop only if it still answers the same.

**D5 — What the manifest carries beyond the runtime.** Measured against the installed runtime in phase 2, never
assumed: `libcrypt.so.1` (Ktor's libcurl; if the runtime lacks it, libxcrypt built as a module from its pinned
release tarball with `--enable-obsolete-api=glibc`), libsecret, poppler-glib, glycin's loaders (WebP), GStreamer's
`good`/`bad` plugins and `giostreamsrc`, the CA bundle at the path the linked libcurl was compiled to read, and the
zone database at `/etc/localtime`. Each missing one is a module with a `sha256`-pinned source. **Codecs:** the app ships
none (owner's patent answer of the Depot slice); `codecs-extra` arrives with the runtime (Context), so a medium plays
what it decodes, and what it does not is said as today with Save a copy. **Locale:** the catalogs install to
`/app/share/locale` and a release build binds there (`words.rs` reads `VEYQ_LOCALE_DIR`, a debug build's path, at
compile time today: the Flatpak build sets it to `/app/share/locale`); the manifest sets `separate-locales: false`,
because a single-file bundle carries only the app's ref and a split-off `.Locale` extension would leave every word
English (remembered from flatpak-builder's default; phase 2 measures German words from the installed bundle).
**Metadata:** the desktop entry and icons `generate_theme.py --gtk` writes today, installed to `share/applications` and
`share/icons/hicolor`, plus an AppStream metainfo file (name, summary, licence `AGPL-3.0-only`, homepage, one
`<release>` with the version and the commit's date, not the build's), held by `appstreamcli validate`.

**D6 — Windows: single-project MSIX through the Windows App SDK's own MSBuild tooling, self-contained, Release.**
`WindowsPackageType` becomes `MSIX` for the package build (the debug loop of `run.py build`/`run` stays unpackaged, so
the existing passes and `Veyq.Drive`'s launch keep working), `WindowsAppSDKSelfContained` and `SelfContained` stay true
(a sideloaded package then needs no `Microsoft.WindowsAppRuntime` framework installed), `-c Release` (first Release
build of the app: phase 4 runs its tests under it too). `makeappx` and `signtool` are the pinned NuGet
`Microsoft.Windows.SDK.BuildTools`' — no Windows SDK install, **no WinApp CLI** (pre-1.0, and it would only wrap the
two tools the lock already pins), no MSIX Packaging Tool (a GUI repackager for installers we do not have). A committed
`Package.appxmanifest`: capabilities `runFullTrust` and `internetClient` only (`webcam` arrives with the scanner), no
`broadFileSystemAccess`; `TargetDeviceFamily Windows.Desktop` with the MinVersion the project's
`TargetPlatformMinVersion` gives; the identity's version is `<major>.<minor>.0.0` (each part ≤ 65535, revision 0 as a
store requires); its PNG assets (Square44x44 with target sizes, Square150x150, Wide310x150, StoreLogo; scales 100–400)
are generated by `generate_theme.py --winui` beside the `.ico`, never committed. **No package dependency on the WebP or
media extensions:** a sideloaded MSIX can depend only on framework packages and `Microsoft.WebpImageExtension` is not
one (measured above); it is provisioned in Windows 11's image with the VP9, Web Media, AV1 and HEVC extensions
(measured on the VM's Home edition), so the app keeps decoding through WIC and Media Foundation; where a person removed
it, the members' pictures fall back to initials as they do now (inbox #885 asks whether that deserves a word).

**D7 — Signing the MSIX: at promotion, by a signer the job receives, payload unchanged.** An MSIX installs only signed,
and the signature lives inside the package, so signing changes its bytes. Checks builds the package **unsigned**; its
smoke signs a copy with a certificate it makes on the runner (`New-SelfSignedCertificate`, trusted in the runner's
`TrustedPeople` only for that run) and installs that. `release.yml` gains a Windows job that signs the staged unsigned
package with the release signer and refuses unless the signed package's `AppxBlockMap.xml` (which hashes every payload
file) is byte-identical to the staged one's — so the release's MSIX carries the tested payload. **The signer is an
input, never a file in the tree:** a PFX and its password as secrets of a GitHub environment that only `release.yml`'s
signing job uses, or a cloud signer's `signtool /dlib` arguments — the same step either way; which one the release uses
is owner question 2. A fork or a self-builder signs with their own: `run.py package --sign <pfx>` reads the password
from the environment. **The manifest's `Publisher` must equal the certificate's subject**, so it is a build input
(`VEYQ_MSIX_PUBLISHER`), defaulting to the release's; a self-built package has its own package family and installs
beside, not over, the released one. Snapshots are not Windows-signed (they are cosign-signed like everything else, D8).

**D8 — Provenance for both: the release's cosign identity and the signed manifest.** Each package is listed by sha256
in `manifest.json` under a new key **`apps`** (name → sha256), never under `assets`: `veyqctl` downloads every `assets`
entry into an instance and the lanes attest the asset set's hash, and app packages are neither the recipe nor
installed by a lane. `veyqctl` ignores the key (it reads named keys only: `parse_manifest`), so no `veyqctl` change and
no new floor. Each package also gets a `cosign sign-blob` bundle as `veyqctl` does, so a person verifies one file
without the manifest. The Flatpak bundle carries no GPG signature of its own (it has no repository to verify against,
D9).

**D9 — Distribution and updates: the release asset, no repository of our own in this CR.** A bundle installs with
`flatpak install --user <file>`; the GNOME runtime comes from Flathub, which every Flatpak desktop has (the bundle names
it with `--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo`, so a desktop without the remote is offered
it). That the runtime comes from Flathub is the one central dependency, stated on the install page; an offline host can
take the runtime as a bundle of its own (`flatpak build-bundle --runtime`), documented, not shipped (1.2 GB). The MSIX
installs by double-click (App Installer) or `Add-AppxPackage`. **Update:** a person installs the next release's file;
the MSIX upgrades in place keeping the app's data (same identity, higher version), the Flatpak bundle upgrades by
installing the newer bundle (phase 2 measures the exact command — `flatpak install --user` again, or
`--reinstall` — and the page says the measured one). No auto-update, no self-hosted OSTree repository and no
`.appinstaller` feed here: each needs a host and a long-lived signing key, and both stores give it for free if the owner
wants them (owner questions 4, 5; inbox #883).

**D10 — Built in Checks, staged as OCI artifacts, promoted like the images.** Checks gains three jobs, each with a
budget: **`app-inputs`** on `macos-latest` (the one host the Kotlin side has ever been built on, and the only one that
renders the icons, Quick Look): Gradle's wrapper validation, then the three release libraries, the bridge's Rust and C#,
the words and the theme, uploaded as one run artifact; **`flatpak`** on `ubuntu-latest` and `ubuntu-24.04-arm` (each
architecture natively: flatpak-builder runs the SDK, no emulation), in the pinned container of D11, building, linting
(`flatpak-builder-lint` from `org.flatpak.Builder`, its manifest and repo checks) and installing the bundle, then starting
the app headless until it logs its sign-in; **`msix`** on `windows-latest` (x64): the locked restores and the
deprecation gate as `run.py build` does, `dotnet test` of both test projects in Release, the package built, a signed
copy installed and started until it logs its sign-in, then uninstalled. `green` needs all three. **Staging:** on a push
(`kind == 'stage'`) each package is pushed by **ORAS** to `ghcr.io/<owner>/veyq-apps:build-<sha>-<platform>` (an OCI
artifact; `cosign` signs it by digest at snapshot exactly as an image); ORAS because a release asset is a file, not an
image, and ORAS is the OCI project's own vendor-neutral client — a `FROM scratch` image would carry it but needs
container tricks to take it out again. **Snapshot** names and signs them (`<major>.<minor>-SNAPSHOT`, `sha-<commit>`,
the snapshot identity); **staging retention** deletes `veyq-apps`' staging tags with the images'; **promotion**
verifies the snapshot signature, gives `<major>.<minor>`, signs under the release identity, pulls the files, signs the
MSIX (D7), writes `apps` into the manifest before the manifest is signed, `cosign sign-blob`s each package and attaches
them. **A pull request** builds and smoke-runs all three and stages nothing.

**D11 — One command per platform, the same in CI and on the developer's machine.** `python3 src/mobile/linux/run.py
flatpak [--arch x86_64|aarch64]` (the Kotlin and generation steps here, then flatpak-builder in a container pinned by
digest — Fedora 45 with Fedora's `flatpak` and `flatpak-builder`, run with the privileges bubblewrap needs there — into
`src/mobile/build/linux/flatpak/veyq-<version>-<arch>.flatpak`) and `python3 src/mobile/windows/run.py package [--sign
<pfx>]` (here, then on the Windows host). Both take the version from the tree as the pipeline's `version` job counts it
(one implementation: `grep -n "def .*release\|rev-list" scripts/release_assets.py` finds the producer's; reuse it, never
a second count). "Reproducible" here means **one command over pinned inputs** — the runtime and SDK by commit, the
container by digest, crates by `Cargo.lock`, NuGet by lock files, Gradle by its verification metadata — not bytes
identical across two builds (an OSTree commit and a ZIP carry times); that is why the release carries the tested bytes
and never rebuilds (D10). The Mac builds only aarch64 locally; x86_64 is CI's.

## Inventory

| Where | What changes | Locator |
|---|---|---|
| `src/mobile/linux/flatpak/` (new) | the manifest (`<app-id>.yml`), the metainfo, the runtime pin, the build container | — |
| `src/mobile/linux/run.py` | the `flatpak` verb; `build` proves in the SDK (D4) | `grep -n "def \|VERBS\|add_parser" src/mobile/linux/run.py` |
| `src/mobile/linux/build.rs`, `src/words.rs` | the release locale directory (D5) | `grep -n VEYQ_LOCALE_DIR src/mobile/linux/build.rs src/mobile/linux/src/words.rs` |
| `src/mobile/theme/generate_theme.py` | MSIX PNG assets under `--winui`; metainfo or the desktop entry's install names under `--gtk` | `grep -n "def \|--winui\|--gtk" src/mobile/theme/generate_theme.py` |
| `src/mobile/windows/Veyq/` | `Package.appxmanifest`, the package build's properties | `grep -n "WindowsPackageType\|SelfContained\|RuntimeIdentifier" src/mobile/windows/Veyq/Veyq.csproj` |
| `src/mobile/windows/run.py` | the `package` verb; Release tests | `grep -n "BUILD_APP\|APP_OUT\|def " src/mobile/windows/run.py` |
| the app id | everywhere it is spelled, if owner question 1 changes it | `git grep -n "me\.raddatz\.veyq" -- src/mobile site docs` |
| `.github/workflows/checks.yml` | `app-inputs`, `flatpak`, `msix`; `green.needs`; budgets | `grep -n "^  [a-z-]*:$\|needs:" .github/workflows/checks.yml` |
| `.github/workflows/publish.yml`, `release.yml` | snapshot names and signs `veyq-apps`; promotion pulls, signs (MSIX on Windows), lists, attaches | `grep -n "imagetools create\|cosign sign\|gh release upload" .github/workflows/publish.yml .github/workflows/release.yml` |
| `scripts/snapshot.py`, `promote.py`, `staging_retention.py`, `release_assets.py` | the `veyq-apps` package and the manifest's `apps`; their selftests | `grep -n "veyq-tools\|veyq-admin" scripts/snapshot.py scripts/promote.py scripts/staging_retention.py scripts/release_assets.py` |
| guards | `validate_build_input_pins.py` check 7 (the Flatpak pins); `validate_release_identity.py`, `validate_pipeline_shape.py`, `validate_action_pins.py`, `validate_image_pins.py` see the new jobs, actions and the container | `grep -ln "checks.yml\|publish.yml\|release.yml" scripts/validate_*.py` |
| docs | the new ADR; native-apps § Platforms and § Open; release-and-deployment § The pipeline, § Publishing, § Releasing, the release table; security-model § What a trusted device rests on (Linux under the Secret portal) | `git grep -n "Flatpak\|MSIX\|packaging" -- docs/crosscutting` |
| site | a new Use page *Install the app* (EN+DE); Security *Verify it yourself* § Verifying a signed release (an app package); *Known limitations* (Flathub runtime, the MSIX certificate, CPU features); `infra/README.md` § Cutting a release (the signer) | `git grep -n "veyqctl.bundle\|sign-blob" -- site/src/content/docs infra/README.md` |

## Phases

- [ ] **1 docs — the ADR and the decided state.** Write the next free ADR (`ls docs/decisions | tail -3`) from D1–D11
  with their reasons and the rejected alternatives named here (WinApp CLI, the MSIX Packaging Tool, a self-hosted OSTree
  repository, flatpak-cargo-generator, a scratch image for staging, building the core inside the SDK); move native-apps'
  Flatpak and MSIX bullets to "decided, being built" pointing at it; release-and-deployment § Decided, not built gains
  the app packages. If owner questions 1–3 are not answered by then, the ADR records the recommendation as the default
  and the phase that needs the answer waits. · **Done when:** `for f in scripts/validate_*.py; do /usr/bin/python3 "$f"
  || echo "RED $f"; done` (guard_gaps skipped) prints no `RED`.
- [ ] **2 linux — the Flatpak builds and runs, aarch64, on the developer's machine and the VM.** The container, the
  pin file, the manifest, the metainfo, `run.py flatpak`, the locale directory. **First measurements, each a
  stop-and-ask if it fails:** (a) flatpak-builder runs in the pinned container under Docker on the Mac (bubblewrap's
  privileges); (b) which of D5's libraries the installed runtime lacks (`flatpak run --command=sh org.gnome.Platform//51
  -c 'ls /usr/lib/*/libcrypt.so.1 …'` or an `ldd` of `libveyq.so` inside the sandbox). Then on the Fedora VM (installing
  flatpak-builder or runtimes there is allowed: the VM is disposable), driven in its GNOME session as the Depot slice's
  VM pass was (ADR 0123's Consequences, the Linux app), against the dev instance through the tunnel that pass used:
  install from the bundle file; sign in; German words under `LANGUAGE=de`; the members' pictures (WebP through glycin);
  a receipt's PDF; a medium played (H.264 and VP9); a file uploaded from and saved to the **document portal's** path,
  opening in the core (ADR 0130's open question); a drop from Files; an https request out of the sandbox (the exchange
  rates, `VeyqAccount.outside`); **D3's Secret measurement** — stay signed in over the Secret portal, restart, unlock;
  then the same with `--talk-name` + `SECRET_BACKEND=service`; keep the one D3 says and record both; update by installing
  a bundle of a later commit (the measured command); `flatpak uninstall --delete-data` leaves nothing under
  `~/.var/app/<id>`. · **Done when:** `python3 src/mobile/linux/run.py flatpak` exits 0 and writes the aarch64 bundle;
  `appstreamcli validate` and `flatpak-builder-lint manifest`/`repo` are clean or each remaining warning is named with
  why; the VM pass's list above is recorded in ADR 0123's Consequences with a screenshot per screen as the final proof;
  `python3 src/mobile/linux/run.py build` green.
- [ ] **3 linux — the SDK proves the app; the pins are guarded.** `run.py build` runs clippy, test and build in
  `org.gnome.Sdk//51` (D4). `validate_build_input_pins.py` check 7: the Flatpak manifest's `runtime-version` equals the
  pin file's branch; every pin line is a 64-hex commit per architecture for Platform, Sdk and the Rust extension; every
  manifest source that is fetched (`type: archive`/`file` with a URL) carries a `sha256`; the build container's `FROM`
  is digest-pinned (already `validate_image_pins.py`'s). Bite each clause by snapshot-and-restore on a detached worktree
  (`changes/README.md` § Bite-proof a guard). · **Done when:** the guard is green on the tree and red, by its own label,
  on each of the four injections; `run.py build` green in the SDK.
- [ ] **4 windows — the MSIX builds, installs and runs on the VM.** `Package.appxmanifest`, the PNG assets,
  `run.py package [--sign]`, Release tests. **First measurements, each a stop-and-ask:** (a) a signed MSIX installs and
  **starts** on the VM as it is, UAC off — if Windows refuses, the owner turns UAC on (`EnableLUA 1`, reboot, a console
  logon: an owner step, also wanted by native-apps' open drop) before the rest; (b) the self-contained Windows App SDK
  inside the package starts; (c) signing a copy leaves `AppxBlockMap.xml` byte-identical (`makeappx unpack` of both,
  compare) — D7 rests on it. Then the pass, through `Veyq.Drive` (its launch learns the packaged app's AUMID): sign in;
  German words; the members' pictures; Windows Hello enrolment (the owner answers the PIN) and a restart that unlocks;
  a file picked by path opening in the core and a Save a copy; a medium played; **the drop from Explorer** (the open
  question native-apps carries); an upgrade by installing a later version keeps the device trust; uninstall removes the
  app's data. The development certificate is made on the VM by `run.py package --sign` when none is given, its key never
  leaves the VM. · **Done when:** `python3 src/mobile/windows/run.py package --sign` exits 0 and the pass above is
  recorded in ADR 0123's Consequences with a screenshot per screen; `run.py build` green (the unpackaged debug loop
  unchanged).
- [ ] **5 ci — Checks builds, tests, smoke-runs and stages the packages.** D10's three jobs, the ORAS step, `green.needs`,
  the budgets (estimates until the first run replaces them), every new action pinned by commit. The guards that read the
  workflows are updated in the same commit, each new assertion bitten (`validate_release_identity.py` that `veyq-apps`
  is pushed only by a push run's stage step and holds `packages: write` only there; `validate_pipeline_shape.py` the
  budgets; `validate_action_pins.py` the pins). · **Done when:** the guards are green and their bites red; a push to
  `main` (pushing is fine, owner 2026-11-11) shows a green Checks run on GitHub with the four packages staged under
  `build-<sha>-*` and both smokes' log lines; a pull request's run builds them and stages nothing (read in its log).
  `scripts/run_pipeline.py` cannot run a macOS or Windows job: the GitHub run is the proof, as for the stack lanes.
- [ ] **6 release — snapshot and promotion carry the packages.** `publish.yml`'s snapshot names and signs `veyq-apps`;
  `staging_retention.py` covers it; `release_assets.py` writes `apps` (and refuses an `apps` name that collides with an
  asset); `promote.py`'s check verifies the snapshot signatures of the app artifacts and their `sha-<commit>` naming the
  staged digests; `release.yml` gains the Windows signing job (D7: block-map equality, refusal named) between `promote`
  and `publish`, and `publish` verifies each package's bundle as it verifies `veyqctl`'s. The signer's secrets live in a
  GitHub environment only that job names — an **owner step** (creating the environment and its secrets) asked for when
  this phase starts. · **Done when:** `python3 scripts/promote_selftest.py`, `snapshot_selftest.py`,
  `staging_retention_selftest.py` and `python3 src/tools/veyqctl_selftest.py` (a manifest with `apps` still installs)
  green, each new refusal with a case; the guards green; a dispatched Publish (owner's dispatch) shows the snapshot's
  `veyq-apps` tags signed (`cosign verify` with the snapshot identity); the promotion's first real run is the next
  release the owner cuts, and its files are verified as the site's page says — recorded in ADR 0057/0091's
  Consequences, or named as owed if no release falls inside the CR.
- [ ] **7 site — install, update, verify.** A Use page *Install the app* (`getting-started/install-the-app.mdx` and
  `de/…`, in the depth of its neighbours, `sources:` the new ADR and native-apps): Linux (the bundle, the runtime from
  Flathub, the measured install and update commands, what the app may reach and why — D3 in plain words), Windows
  (double-click or `Add-AppxPackage`, the certificate step if owner question 2 makes one, x64 under emulation on ARM),
  the CPU requirement (native-apps: AES, on x86-64 also PCLMUL and AVX), what the apps hold and do not hold yet (owner
  question 3), uninstall. *Verify it yourself* § Verifying a signed release: `cosign verify-blob` of a package against
  the release identity, and its sha256 in the manifest's `apps`. *Known limitations*: the runtime from Flathub, the
  MSIX's trust, no auto-update. `infra/README.md` § Cutting a release: the signer's environment and its rotation. Each
  step the page tells a person to take is walked on the VMs (phases 2 and 4 walked them; a changed step is walked again).
  · **Done when:** `pnpm run check` in `site/` green; the guards green.
- [ ] **8 harvest.** Native-apps § Platforms and § Open say what was built and measured; what this CR taught moves to
  where it binds (`src/mobile/CLAUDE.md` § Traps for the sandbox's and the package's traps; `changes/README.md` for a
  working lesson); the CR is deleted. · **Done when:** the guards green with the CR gone.

## Definition of done

Both packages build by their one command from a clean checkout, install and run on the test VMs through the passes of
phases 2 and 4, and are built, smoke-run and staged by every Checks push run; a snapshot signs them and the promotion
attaches them, Windows-signed with an unchanged block map and listed under `apps` in the signed manifest. No secret is in
the tree (`git grep -nI "BEGIN .*PRIVATE KEY\|\.pfx" -- . ':!*.md'` finds nothing of ours). The pins of the Linux build
are a guard's (check 7, bitten). `satisfies:` names no use case: packaging adds no user operation the use cases do not
already describe; the two concerns it changes ([native-apps](../docs/crosscutting/native-apps.md), [release-and-deployment](../docs/crosscutting/release-and-deployment.md)) are updated and the coverage
validator stays green (`/usr/bin/python3 scripts/validate_coverage.py`). The site's *Install the app* page exists EN+DE,
walked, and `pnpm run check` in `site/` is green. Every guard but guard_gaps green at each phase's commit.

## Owner questions

1. **The app's identity.** The Flatpak app id and the MSIX package name are permanent: changing either after people
   installed means a second app beside the first. Today every platform says `me.raddatz.veyq` (`git grep -n
   "me\.raddatz\.veyq" -- src/mobile`), a domain of the owner's name; Flathub verifies an id against the domain it
   names, and the project's domain is `veyq.app`. **Recommendation:** `app.veyq.Veyq` for both desktop packages, decided
   before the first package is published; Android's and Apple's ids decided with their distribution (inbox #884),
   since nothing is distributed there yet.
2. **Who signs the released MSIX, under which publisher name.** (a) A project certificate of our own (free; the subject
   is free text, e.g. `CN=veyq`; a person trusts the published `.cer` once, as administrator, before the first install;
   the key a GitHub environment secret); (b) Azure Artifact Signing (a monthly fee; an identity validation whose legal
   name is in the certificate; trusted by Windows with no extra step); (c) an OV certificate from a CA, on a cloud HSM
   (yearly fee, the same legal-name question). **Recommendation:** (a) now — it costs nothing and keeps no person's
   legal name in a certificate — and the Microsoft Store (question 4) as the step-free way for most people; (b) only
   if sideloading without a certificate step matters more than the fee.
3. **When packages appear in a release.** The desktop apps hold Expenses and Depot, not Memories or Settings
   (native-apps § Built). **Recommendation:** attach them from the first release after phase 6, the install page
   saying plainly that the apps are a preview and listing what they hold; building and staging start with phase 5
   regardless.
4. **The Microsoft Store** (inbox #882): a free individual account; the Store signs, updates and vouches for the
   package; it needs a listing (name, description, screenshots, privacy policy URL, age rating) and Partner Center's
   publisher identity, so the Store's package is a second family beside the sideloaded one. **Recommendation:** yes,
   as its own CR after this one.
5. **Flathub** (inbox #881): the usual Linux channel, with updates; it reviews the manifest and prefers apps built from
   source on its own builders, which the Kotlin/Native core (D4) does not fit without a prebuilt-library exception.
   **Recommendation:** ask Flathub after question 1 is settled, as its own CR; the release bundle stays the
   self-hostable path either way.

## Retrospective

_(filled at close)_
