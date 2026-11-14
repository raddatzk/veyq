# ADR 0139 — The desktop apps ship as a Flatpak bundle and an MSIX, built in Checks and promoted with the release

- Status: Accepted
- Built: partly (written, none of it run yet: decision 10's Checks jobs, its first GitHub run owed and with it the x86_64 bundle of decision 1; the snapshot's, the retention's and the promotion's part with decision 8's `apps`; decision 7's signing job, whose signer — the certificate and the environment's secrets — is the owner's step before the first release that carries a package)
- Date: 2026-11-13
- Model: [native-apps](../crosscutting/native-apps.md)

## Context

The Linux app (GTK 4 and libadwaita in Rust, `src/mobile/linux`) and the Windows app (WinUI 3 in C#, `src/mobile/windows`)
([ADR 0136](0136-an-apps-logic-is-the-kotlin-cores-each-platform-draws-only-its-own-ui.md) decisions 4 and 5) ran only
from a developer's build: Linux in a Fedora container under Broadway or in the test VM's session, Windows unpackaged and
copied onto the VM. Neither could be handed to a person. Each needs a package a person installs, built from this
repository, carried by a release and verifiable without a store — and each platform's package format brings its own
sandbox, signing and update story.

What constrains the choice:

- **Self-hostable by default** (root `CLAUDE.md`): a package must be buildable by anyone from the tree and installable from a
  release file, with no store, no Flathub account and no host veyq runs. A store listing is optional on top.
- **A release is a promoted snapshot** ([ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md)
  decision 5): the bytes a release attaches are the bytes a Checks run built and tested, never rebuilt.
- **The precise release reaches no wire** ([ADR 0057](0057-release-identity-and-deployment-recipe.md) decision 4): a
  package's metadata may name its version; the app's code does not send it.
- **The core is Kotlin/Native** ([ADR 0123](0123-the-apps-share-a-kotlin-multiplatform-core-under-native-ui.md)): its Gradle
  build downloads its own distribution and toolchains and links against its own sysroot, never a system compiler, and has
  no Windows ARM64 target.

## Decision

1. **Formats and architectures.** Linux: one single-file Flatpak bundle per architecture,
   `veyq-<major>.<minor>-x86_64.flatpak` and `veyq-<major>.<minor>-aarch64.flatpak`. Windows: one MSIX,
   `veyq-<major>.<minor>-x64.msix` — no arm64 MSIX and no `.msixbundle`, because Kotlin/Native has no Windows ARM64
   target; the x64 app runs on Windows on ARM under its emulation. The x86_64 bundle is the Linux app's first x86-64
   build; CI's smoke on an x86-64 runner is its first run.

2. **The Linux runtime is `org.gnome.Platform//51` with its SDK and `org.freedesktop.Sdk.Extension.rust-stable//26.08`,
   pinned by commit.** 51 is the newest branch (the longest supported), the test VM's own GNOME, and carries GTK and
   libadwaita at or above the feature levels the app is written against (a newer library under older feature flags is
   the gtk-rs norm). The manifest names the branch (`runtime-version: '51'`); a pin file beside it
   (`src/mobile/linux/flatpak/runtime.pins`) names, per architecture, the commit of the Platform, the Sdk and the Rust
   extension a build installs (`flatpak update --commit=`), and a guard holds the two together
   (`validate_build_input_pins.py`). A person's machine runs whatever commit of branch 51 Flathub serves — the runtime's
   ABI is stable within a branch; moving to 52 is a pin bump, done when 51 nears its end of life.

3. **The sandbox gets the least that works.** `--share=network` (the API and the store's URLs); `--socket=wayland`,
   `--socket=fallback-x11`, `--share=ipc`, `--device=dri` (GTK's drawing); `--socket=pulseaudio` (a medium's sound
   through GStreamer's sink, which PipeWire answers). **No `--filesystem`:** files come and go through the FileChooser
   and document portals, which GTK uses by itself in a sandbox, and a drop from Files arrives as a document-portal path.
   **The secure store: the app's own keyring behind the Secret portal, reached with
   `--talk-name=org.freedesktop.secrets`.** Inside a sandbox whose desktop offers the Secret portal, libsecret keeps
   every item in a keyring of the app's own (`~/.var/app/<id>/data/keyrings`, opened by a secret the portal hands out
   from the host's keyring), whatever `SECRET_BACKEND` says; but the core first asks whether a Secret Service answers
   (`secret_service_get_sync`), which only the session bus's `org.freedesktop.secrets` answers — without the name that
   one call fails and nothing is offered after a sign-in. So the manifest grants the name, and the items still land in
   the app's own keyring (both measured on the test VM, native-apps § Platforms); where a desktop has no Secret portal,
   libsecret uses the host's collection through the same name (remembered from libsecret's backend choice, not measured). The alternative — the core asking libsecret's backend
   instead of the service, which would need no name at all — is a core change, named in native-apps § Open.
   [security-model](../crosscutting/security-model.md) § What a trusted device rests on says what the device then
   offers. **The camera** adds nothing
   here: the Camera portal hands the app a PipeWire descriptor. No other `--talk-name`, no `--device=all`, no
   `--filesystem=home`, no bare `--socket=x11`.

4. **The core and everything generated are built outside the sandbox and handed in.** flatpak-builder builds offline in
   the SDK, and Kotlin/Native's build needs the network and its own toolchain, so it would gain nothing there. The
   release `libveyq.so`, the bridge's generated Rust and samples, the words' catalogs and the theme are produced first
   (as `run.py build` produces them) and enter the manifest as `type: dir` sources; the Rust crates enter as `cargo vendor
   --locked` output — cargo's own command, checked against `Cargo.lock`'s checksums, no generator tool added. The app's
   module then runs, inside `org.gnome.Sdk//51` with `rust-stable`, `cargo clippy --locked -- -D warnings`, `cargo test
   --locked` (all but what a build's sandbox cannot run, each named in `run.py` with why) and `cargo build --release
   --locked`: the app is built and tested against the runtime it ships on (ADR 0136
   decision 4). `run.py build` proves the app in that SDK; the Fedora container stays the environment of `run.py run`'s
   Broadway passes and `run.py test`'s core binary — a pass's environment, not the proof of the app.

5. **The manifest carries only what the runtime lacks, measured against the installed runtime, never assumed** — of
   `libcrypt.so.1` (Ktor's libcurl), libsecret, poppler-glib, glycin's loaders (WebP), GStreamer's plugins and
   `giostreamsrc`, the CA bundle where the linked libcurl reads it and the zone database at `/etc/localtime`; each
   missing one is a module with a `sha256`-pinned source. **Codecs:** the app ships none (the owner's patent answer of the
   Depot slice); the runtime's `codecs-extra` extension arrives with it, so a medium plays what that decodes, and what it
   does not is said as today, with Save a copy. **Locale:** the catalogs install to `/app/share/locale` and the release
   build binds there; the manifest sets `separate-locales: false`, because a single-file bundle carries only the app's
   ref and a split-off `.Locale` extension would leave every word English. **Metadata:** the desktop entry and icons
   `generate_theme.py --gtk` writes, installed to `share/applications` and `share/icons/hicolor`, and an AppStream
   metainfo file (name, summary, licence `AGPL-3.0-only`, homepage, one `<release>` with the version and the commit's
   date — the commit's, so the same tree gives the same file), held by `appstreamcli validate`.

6. **Windows: a single-project MSIX through the Windows App SDK's own MSBuild tooling, self-contained, Release.**
   `WindowsPackageType` becomes `MSIX` for the package build only (the unpackaged debug loop stays, with every pass that
   drives it); `WindowsAppSDKSelfContained` and `SelfContained` stay true, so a sideloaded package needs no
   `Microsoft.WindowsAppRuntime` framework installed; `-c Release`, under which the tests run too. `makeappx` and
   `signtool` are the locked NuGet `Microsoft.Windows.SDK.BuildTools`' — no Windows SDK install. A committed
   `Package.appxmanifest.in` (a template `run.py package` fills with decision 12's identity and the version) declares
   `runFullTrust`, `internetClient` and `webcam` (the QR scan's camera) only, no
   `broadFileSystemAccess`; `TargetDeviceFamily Windows.Desktop` at the project's `TargetPlatformMinVersion`; the
   identity's version is `<major>.<minor>.0.0` (each part at most 65535, the revision 0 a store requires); its PNG assets
   are generated by `generate_theme.py --winui` beside the `.ico`, never committed. **No package dependency on the WebP
   or media extensions:** a sideloaded MSIX can depend only on framework packages, and `Microsoft.WebpImageExtension` is
   not one; Windows 11's image provisions it with the VP9, Web Media, AV1 and HEVC extensions, so the app keeps decoding
   through WIC and Media Foundation, and where a person removed one the members' pictures fall back to initials as they
   do now.

7. **The MSIX is signed at promotion, by a signer the signing job receives, its payload unchanged.** An MSIX installs only
   signed, and the signature lives inside the package, so signing changes its bytes. Checks builds it **unsigned**; its
   smoke signs a copy with a certificate made on the runner for that run and installs that. The release's Windows job
   signs the staged unsigned package with the release signer and refuses unless the signed package's `AppxBlockMap.xml`
   (which hashes every payload file) is byte-identical to the staged one's — so the release's MSIX carries the tested
   payload. **The signer is an input, never a file in the tree:** a PFX and its password as secrets of a GitHub
   environment only the signing job names, or a cloud signer's `signtool /dlib` arguments — the same step either way.
   **Which signer is expected is the tree's, though** (refined while building it, 2026-11-13): the certificate's public
   half is committed as `src/mobile/windows/veyq.cer`, and the job (`scripts/msix_sign.py sign`) refuses unless that
   certificate's subject is the publisher and the signed package's one signer is that certificate — so a secret
   swapped without a reviewed commit signs nothing, and a rotation is a commit. It also refuses unless signing added
   exactly `AppxSignature.p7x` and `AppxMetadata/CodeIntegrity.cat`, rewrote nothing but `[Content_Types].xml`, and
   left `AppxBlockMap.xml` and every payload file byte-identical (what signing does, measured on a pair the VM signed).
   signtool comes from the build's pinned `Microsoft.Windows.SDK.BuildTools`, its `.nupkg` held to a measured
   SHA-256, not from the runner's Windows SDK. The signature carries **no timestamp**: no timestamp authority is in
   the release's path, so a released package installs while its certificate is valid — the certificate's validity is
   the packages' installable life, and a rotation comes before it ends.
   **The release's signer is the project's own self-signed certificate**, subject `CN=veyq` (decision 12; the owner,
   2026-11-13): its PFX and password are that environment's secrets. A self-builder signs with their own (`run.py package --sign <pfx>`, the password from the environment). The
   manifest's `Publisher` must equal the certificate's subject, so it is a build input defaulting to the release's; a
   self-built package has its own package family and installs beside, not over, the released one. Snapshots are not
   Windows-signed; they are cosign-signed like everything else (decision 8).

8. **Both packages carry the release's provenance: its cosign identity and the signed manifest.** Each package is listed
   by sha256 in `manifest.json` under a key of its own, **`apps`** (name → sha256), never under `assets`: `veyqctl`
   downloads every `assets` entry into an instance and the lanes attest the asset set's hash, and an app package is
   neither the recipe nor installed by a lane. `veyqctl` reads named keys only, so it ignores `apps` — no `veyqctl`
   change, no new floor. Each package also gets a `cosign sign-blob` bundle, as `veyqctl` does, so a person verifies one
   file without the manifest. The Flatpak bundle carries no GPG signature of its own: it has no repository to be
   verified against (decision 9).

9. **Distribution is the release file; updates are the next release's file.** A bundle installs with `flatpak install
   --user <file>`; the GNOME runtime comes from Flathub, which every Flatpak desktop has — the bundle names it
   (`--runtime-repo=https://dl.flathub.org/repo/flathub.flatpakrepo`), so a desktop without the remote is offered it.
   That the runtime comes from Flathub is the one central dependency, said on the install page; an offline host takes the
   runtime as a bundle of its own (`flatpak build-bundle --runtime`), documented, not shipped. The MSIX installs by
   double-click (App Installer) or `Add-AppxPackage`. A person updates by installing the next release's file: the MSIX
   upgrades in place keeping the app's data (same identity, higher version), the Flatpak bundle by the same
   `flatpak install --user <file>`, which upgrades the installed app in place and keeps its data (native-apps
   § Platforms). No auto-update, no repository of our own and no `.appinstaller`
   feed: each needs a host and a long-lived signing key, and a store gives it if one is wanted.

10. **Built in Checks, staged as OCI artifacts, promoted like the images.** Checks gains three jobs: **`app-inputs`** on
    macOS (the one host the Kotlin side has been built on, and the only one that renders the icons): Gradle's wrapper
    validation, the three release libraries, the bridge's Rust and C#, the words and the theme, as one run artifact;
    **`flatpak`** on an x86-64 and an arm64 Linux runner, each architecture natively, in decision 11's container:
    the bundle built, linted (`flatpak-builder-lint`'s manifest and repo checks) and installed, the app started headless
    until it logs its sign-in; **`msix`** on x64 Windows: the locked restores and the deprecation gate, both test
    projects in Release, the package built, a signed copy installed and started until it logs its sign-in, then
    uninstalled. `green` needs all three. On a push the packages are pushed by **ORAS** to
    `ghcr.io/<owner>/veyq-apps:build-<sha>-<platform>`, an OCI artifact cosign signs by digest at snapshot exactly as an
    image — by a fourth job, `apps-stage`, on a push alone, which takes the three as the build jobs' artifacts and
    checks out nothing, so the token that can write meets no code of the commit and the jobs that run that code hold no
    write (refined while building it, 2026-11-13; `validate_release_identity.py` holds it); the snapshot names and signs them, staging retention deletes their staging tags with the images', and the
    promotion verifies the snapshot signature, re-tags and signs under the release identity, pulls the files, signs the
    MSIX (decision 7), writes `apps` into the manifest before the manifest is signed, `cosign sign-blob`s each package
    and attaches them. A pull request builds and smoke-runs all three and stages nothing. **As built** (2026-11-13): the
    snapshot names each artifact `<version>-SNAPSHOT-<platform>` and `sha-<commit>-<platform>` (ORAS tags the manifest
    `apps-stage` pushed, after `scripts/snapshot.py` held the staging tags to the run's `app-digests` and refused a name
    another run took) and signs it by digest; a release names it `<version>-<platform>` — no channel: a person takes a
    package from its release. The promotion runs `check` (which verifies the packages as the snapshot's and refuses,
    before any write, when the signer's certificate is not committed), then `apps` (pulls them by digest, read only),
    then `msix-sign` on Windows, then `promote` — the Windows signature before the names and the files, so the manifest
    is produced and signed once, after every byte it lists exists. A snapshot of a commit whose checks.yml has no
    `apps-stage` is released without packages.

11. **One command per platform, the same in CI and on a developer's machine.** `python3 src/mobile/linux/run.py flatpak
    [--arch x86_64|aarch64]` — the Kotlin and generation steps on the host, then flatpak-builder in a container pinned by
    digest (`src/mobile/linux/flatpak/Containerfile`: Fedora's `flatpak` and `flatpak-builder` on the same Fedora image
    the development container builds from, run privileged for bubblewrap), into
    `src/mobile/build/linux/flatpak/veyq-<version>-<arch>.flatpak`; and `python3 src/mobile/windows/run.py package
    [--sign <pfx>]`. CI runs the same verbs split where its hosts split them: each `run.py inputs` on macOS, then
    `flatpak --prebuilt` and `package --prebuilt` on the package's own runner (`VEYQ_WINDOWS_SSH=local` there: the
    runner is the Windows host). Both take the version from the tree as the pipeline counts it (`scripts/release_assets.py`'s
    `tree_release`, the one implementation). "Reproducible" means **one command over pinned inputs** — the runtime and
    SDK by commit, the container by digest, crates by `Cargo.lock`, NuGet by its lock files, Gradle by its verification
    metadata — not bytes identical across two builds (an OSTree commit and a ZIP carry times); that is why a release
    carries the tested bytes and never rebuilds (decision 10).

12. **The desktop packages' identity is defined once, in `src/mobile/desktop-identity.json`**: the Flatpak's application
    id (which is also the Linux app's GApplication id, its D-Bus name and its resource path — a sandbox lets an app own
    only names under its own id) and the MSIX's `Publisher`. Every reader — `run.py`, the theme generator, the Linux
    app's build — takes it from there. Both are outward identity, permanent once people install a package: an id changed
    later is a second app beside the first. **The owner decided both (2026-11-13):** `app.veyq.Veyq` — the owner holds
    the domain `veyq.app`, which Flathub verifies an id against — and `CN=veyq`, the subject of **a self-signed
    certificate of the project's own**, which signs the released MSIX (decision 7): free, and no person's legal name in
    it. Windows does not trust it by itself, so a person trusts its public half once, as administrator, before the
    first install — imported into the computer's (Local Machine) *Trusted People* store, the store Windows checks a
    sideloaded package's self-signed certificate against (measured on the test VM, ADR 0123's Consequences, the MSIX).
    **The release carries that public half as a file of its own, `veyq.cer`,** checked like a package (decision 8):
    listed by sha256 under the manifest's `apps` and given a `cosign sign-blob` bundle, so a person verifies the
    certificate they are about to trust against the release identity before trusting it — not built, with the
    promotion. The certificate is made by its owner, with the release's signing environment; its key and password
    are never in the tree, its public half is (decision 7: the tree's pin of the signer). Android's and Apple's identifiers are decided with their own distribution.

## Consequences

- **Phases:** the Linux bundle and its pass first, then the SDK as the Linux app's proving ground and its pins under a
  guard, then the MSIX, then CI's three jobs, then the release's snapshot, signing and promotion, then the site's
  *Install the app* page. What each measured lands in [native-apps](../crosscutting/native-apps.md) and ADR 0123's
  Consequences (the Linux app, the Windows app).
- **Owed by a person, not this record:** the release certificate (`CN=veyq`, decision 12): made, its public half
  committed as `src/mobile/windows/veyq.cer`, its PFX and password stored as the secrets `MSIX_PFX_BASE64` and
  `MSIX_PFX_PASSWORD` of the GitHub environment `msix-signing`, whose protection rules allow `main` and `release/*`
  only — the steps in `infra/README.md` § Cutting a release. Until then a promotion of a snapshot that staged packages
  is refused at `check`, by name, before it writes anything.
- **One central dependency:** a Flatpak desktop fetches the GNOME runtime from Flathub. It is said where the bundle is
  offered, with the offline way around it.
- **What the docs site says.** [Install the app](../../site/src/content/docs/getting-started/install-the-app.mdx) (EN and
  DE) gives a person the install, update and removal steps measured on the VMs, what the sandbox may reach, the CPU it
  needs and that the apps are a preview no release carries yet; it reads decision 12's identity from its file, never
  spells it. [Verify it yourself](../../site/src/content/docs/security/verify-it-yourself.md) § Verifying a signed release
  says how a package will be checked (decisions 7 and 8), and [Known limitations](../../site/src/content/docs/security/known-limitations.md)
  carries the runtime from Flathub, the MSIX's trust and the missing auto-update. The signer's environment and its
  rotation are the maintainer's, in `infra/README.md` § Cutting a release.

## Alternatives considered

- **WinApp CLI** (`microsoft/WinAppCli`). Rejected: pre-1.0, and it would only wrap `makeappx` and `signtool`, which the
  locked `Microsoft.Windows.SDK.BuildTools` already pins.
- **The MSIX Packaging Tool.** Rejected: a GUI repackager of existing installers; there is no installer to repackage.
- **A self-hosted OSTree repository (or an `.appinstaller` feed) for updates.** Rejected for now: each needs a host
  veyq runs and a long-lived signing key; Flathub and the Microsoft Store give updates if the owner wants a listing.
- **`flatpak-cargo-generator`** (a sources list of every crate). Rejected: a generator tool added where `cargo vendor
  --locked`, cargo's own command, gives the same offline crates checked against `Cargo.lock`.
- **A `FROM scratch` image to stage a package.** Rejected: an image would carry the file but needs container tricks to
  take it out again; ORAS, the OCI project's own vendor-neutral client, pushes and pulls the file as it is.
- **Building the core inside the SDK.** Rejected: Kotlin/Native downloads its toolchain and links against its own
  sysroot, which an offline SDK build refuses and gains nothing from.
- **The development container's Fedora as the Flatpak's proving ground.** Rejected: the app would be tested against
  libraries it does not ship on; the SDK is what the runtime is built from.
- **An arm64 MSIX or a `.msixbundle`.** Not open while Kotlin/Native has no Windows ARM64 target.
- **A Fedora 45 build container** (the test VM's release). Not taken: the image-pin guard holds one tag per repository
  across the tree, and Fedora 44's `flatpak-builder` is the same 1.4.12; the builder shares the development container's
  pinned `fedora:44`.
