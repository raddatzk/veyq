#!/usr/bin/env python3
"""The one entry point for everything Linux of the veyq app (ADR 0123's Consequences, the Linux app): it builds the Fedora 44
image of `Containerfile` beside it and runs what a verb names in it. Everything Kotlin is cross-compiled on this host
by Gradle; only running it needs Linux.

Verbs:
  test [--tests <pattern>]   links every core module's Linux test binary for this host's architecture
                             (`:<module>:linkDebugTest<Target>`, the modules read from settings.gradle.kts) and runs
                             each in the container, the platform's first: the conformance suite over
                             libsodium, utf8proc's NormalizationTest, the wall clock in Europe/Berlin, and — with
                             VEYQ_LIVE set — the live sign-in. `--tests` is Kotlin/Native's `--ktest_filter` pattern
                             (`*LiveSignInTest*`). It runs inside a session bus of its own with gnome-keyring-daemon,
                             its login collection unlocked by a password drawn in the container, so the DeviceKeys
                             contract runs over a real Secret Service; without `--tests`, the Secret Service probe
                             runs twice more, without a session bus and with libsecret moved away. Its exit code is
                             the first red test binary's. `--prebuilt` runs the binaries `test-inputs` left in this
                             tree instead of linking them (CI's `core-native-linux` job, after `app-inputs` on macOS;
                             inbox #1102).
  test-inputs [--arch aarch64|x86_64]...
                             what `test` links on this host, alone: every core module's Linux test binary for each
                             architecture named (default both), beside utf8proc's NormalizationTest.txt, at their paths
                             in this tree. CI's `app-inputs` runs it and hands the files to `test --prebuilt`.
  bridge                  links the bridge's release library (`:bridge:linkReleaseShared<Target>`), checks with
                             `nm -D` that it defines exactly bridge.h's six functions and Kotlin/Native's own
                             symbols (none of the static libraries linked in), and builds and runs
                             `bridge/smoke/smoke.c` against it through bridge.h alone; with VEYQ_LIVE set the smoke
                             also signs in as the seeded principal over the library's own HTTP client.
  build                      generates what the app is built from but does not keep — the bridge's Rust
                             (`:bridge:generateBindings`) and the samples its round trip reads (`WireTest`), the words'
                             gettext catalogs and Rust (`generate_words.py --gettext`), the theme
                             (`generate_theme.py --gtk`) — links the bridge's library, and proves the app in the GNOME
                             SDK it ships on (ADR 0139 decision 4): flatpak-builder builds the Flatpak's modules before
                             the app (`--stop-at`, from its cache), and in that build directory, as the app's module
                             would, `cargo clippy --locked --all-targets -- -D warnings`, `cargo test --locked` (all but
                             SANDBOX_SKIPS) and `cargo build --locked` run. The pinned runtime as `flatpak` installs it;
                             the registry and target in the volumes `veyq-flatpak-cargo`/`veyq-flatpak-target-<arch>-<checkout>`.
                             SANDBOX_SKIPS then run in the Fedora 44 container (`cargo test -- --exact`), where glycin
                             runs without a portal. Its exit code is cargo's.
  run [--port N] [--devlogin [--fill|--unlock]] [--no-secret-service | --home VOLUME]
                             `build`'s generation and `cargo build`, then the app under `gtk4-broadwayd`, drawn into a
                             browser at http://127.0.0.1:N (default 8095; published on 127.0.0.1 only). It runs under a
                             session bus of its own with gnome-keyring-daemon, and the home's data directory (the device
                             store, the keyring and the password it opens with) is the volume `veyq-linux-home`, so a
                             second start finds what the first kept (`--home` names another, so a pass keeps a home of
                             its own and removes it after); `--no-secret-service` runs with neither, where
                             nothing is offered after the sign-in and every start signs in. LANGUAGE and
                             ADW_DEBUG_COLOR_SCHEME, when set, reach the app, VEYQ_LOCALE as its LANG (default
                             en_US.UTF-8; `VEYQ_LOCALE=de_DE.UTF-8 LANGUAGE=de` for German words and German
                             money). With VEYQ_LIVE the app reaches the dev origin under its own name (as `test`
                             does); `--devlogin` (needs VEYQ_LIVE) hands the debug build the seeded principal of the
                             env file as VEYQ_DEV_*, and it signs in through its own sign-in — the values go to
                             `docker run` through its environment, never on its command line. `--fill` only fills the
                             sign-in, for the pass to press Sign in; `--unlock` hands the password alone, which the
                             unlock of a password-trusted account takes, and Manage's way back to the password fills
                             its field with it once. VEYQ_DEV_SCAN=1 shows a debug build's QR harness instead of the
                             session (no sign-in); VEYQ_DEV_CAMERA=<image on this host> is its test camera (mounted
                             read-only). The container has no Camera portal, so without it the harness says NoCamera.
                             VEYQ_DEV_SETTINGS=1 opens a debug build's account Settings over a state of its own
                             instead of the session (no sign-in, no server). VEYQ_UNREACHABLE=<host>[,<host>…] maps
                             each host to the container's own loopback, where nothing listens, so a pass sends it
                             nothing (the breached-password range service: its "could not be checked" state).
  lock                       `cargo update`: writes Cargo.lock anew from Cargo.toml (the one way it changes).
  flatpak [--arch aarch64|x86_64]
                             the Flatpak bundle (ADR 0139): the bridge's release library and everything `build`
                             generates, the crates by `cargo vendor --locked`, the metainfo and the manifest filled from
                             `flatpak/` with the id (src/mobile/desktop-identity.json), the version (the tree's, as the
                             pipeline counts it) and the commit's date; then, in the image of `flatpak/Containerfile`
                             (privileged: bubblewrap), the runtime, SDK and Rust extension installed from Flathub at the
                             commits `flatpak/runtime.pins` names (the volume `veyq-flatpak-<arch>` keeps them),
                             `appstreamcli validate`, flatpak-builder (clippy, test and the release build in the SDK),
                             flatpak-builder-lint's manifest and repo checks, and the bundle
                             `src/mobile/build/linux/flatpak/veyq-<version>-<arch>.flatpak`. Default: this host's
                             architecture; another one runs under Docker's emulation. `--prebuilt` takes the release
                             library and the generated sources `inputs` left in this tree instead of making them (CI's
                             `flatpak` job, after `app-inputs` on macOS: no Gradle, no Quick Look). `--smoke` then
                             installs the bundle in the same container, starts it headless in its sandbox (under
                             `dbus-run-session` and `gtk4-broadwayd`, both the runtime's), waits until it logs `session
                             SignIn`, and uninstalls it with its data.
  inputs [--arch aarch64|x86_64]...
                             what `flatpak` builds outside the sandbox, alone (ADR 0139 decision 4): the bridge's release
                             library for each architecture named (default both), the bridge's Rust and the samples its
                             round trip reads, the words and the theme, at their paths in this tree. CI's `app-inputs`
                             runs it on macOS and hands the files to `flatpak --prebuilt`.

cargo's registry and target directory live in the Docker volumes `veyq-linux-cargo` and `veyq-linux-target-<hash>` (one
target per checkout, so parallel worktrees never mix their crates), so a rebuild is incremental; `docker volume rm` both
to start clean.

Live runs: `VEYQ_LIVE=http://veyq.localhost:8080 run.py test --tests '*LiveSignInTest*'` (or `run.py bridge`) signs in
as the seeded principal of `.run/.env` (VEYQ_ENV_FILE names another file, e.g. the main checkout's from a worktree).
The backend picks the tenant from the Host header, so the container keeps the origin's name: a `*.localhost` origin
resolves to the container's own loopback there, where socat forwards its port to the host (`host.docker.internal`),
and every tenant of the account (each a `*.localhost` name on that port) arrives under its own name. A blob's way up or
down (a cover, a receipt) goes to the object store's public endpoint, a dev instance's Vite origin: VEYQ_LIVE_STORE
(e.g. http://veyq.localhost:8081) forwards its port the same way.

Docker must answer `docker build` / `docker run`; the image is `veyq-linux-dev`, rebuilt from its layer cache each run.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
import repo_layout  # noqa: E402

HERE = repo_layout.MOBILE_LINUX
IMAGE = "veyq-linux-dev"
# The Linux app's id (ADR 0139 decision 12): its GApplication id, its D-Bus name, its Flatpak's id. Defined only there.
APP_ID = json.loads((repo_layout.MOBILE / "desktop-identity.json").read_text())["appId"]
# What Kotlin/Native defines in a shared library beside its @CName functions: its C API's entry (`lib<name>_symbols`),
# two of its runtime's, and the ELF init/fini (read with `nm -D --defined-only` on 2026-11-10, Kotlin 2.4.21).
KOTLIN_NATIVE_EXPORTS = {"libveyq_symbols", "IsInstance", "Konan_cxa_demangle", "__cxa_demangle", "_init", "_fini"}
ZONE = "Europe/Berlin"
VECTORS = ROOT / "contracts" / "crypto" / "vectors" / "v1.json"
NORMALIZATION_TEST = repo_layout.MOBILE / "build" / "utf8proc" / "NormalizationTest.txt"
GENERATED = repo_layout.MOBILE / "build" / "generated"
WORDS = repo_layout.MOBILE / "words"
THEME = repo_layout.MOBILE / "theme"
# cargo's registry and its target, kept across runs (named volumes: nothing of them lands in the tree). The target is one
# per checkout: two worktrees building into one target at once mixed their crates (seen 2026-11-18, a merge's build
# compiled against another tree's words and Config). The registry is content-addressed and shared.
CHECKOUT = hashlib.sha256(str(repo_layout.ROOT.resolve()).encode()).hexdigest()[:10]
TARGET_VOLUME = f"veyq-linux-target-{CHECKOUT}"
CARGO_VOLUMES = {"veyq-linux-cargo": "/veyq/cargo", TARGET_VOLUME: "/veyq/target"}
BROADWAY_DISPLAY = 5  # gtk4-broadwayd serves display :N on port 8080 + N
# This host's architecture -> (Docker platform, Kotlin/Native target as Gradle names it)
ARCHES = {"arm64": ("linux/arm64", "LinuxArm64"), "aarch64": ("linux/arm64", "LinuxArm64"),
          "x86_64": ("linux/amd64", "LinuxX64"), "AMD64": ("linux/amd64", "LinuxX64")}


def run(*args: str, cwd: Path | None = None) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)


def build_image(docker_platform: str) -> None:
    run("docker", "build", "--platform", docker_platform, "-t", IMAGE, "-f", str(HERE / "Containerfile"), str(HERE))


def test_modules() -> list[tuple[str, Path]]:
    """The core modules with tests of their own (the shared test support `:testing` has none), the platform first."""
    return [(p, d) for p, d in repo_layout.mobile_core_modules() if any((d / "src").glob("*Test/kotlin"))]


def test_kexe_dirs(target: str) -> dict[str, Path]:
    """Each test module's name as a directory (`apps-memories`) -> where its [target] test binary is linked."""
    return {p.strip(":").replace(":", "-"): d / "build" / "bin" / (target[0].lower() + target[1:]) / "debugTest"
            for p, d in test_modules()}


def missing_test_inputs(target: str) -> list[Path]:
    return [f for f in [*(d / "test.kexe" for d in test_kexe_dirs(target).values()), NORMALIZATION_TEST] if not f.exists()]


def test_inputs(flatpak_arches: list[str]) -> int:
    """`test`'s part on this host, alone: every test module's test binary for each of [flatpak_arches] linked (CI's
    `app-inputs`)."""
    targets = [FLATPAK_ARCHES[a][1] for a in flatpak_arches]
    run("./gradlew", *(f"{p}:linkDebugTest{t}" for t in targets for p, _ in test_modules()), cwd=repo_layout.MOBILE)
    missing = [f for t in targets for f in missing_test_inputs(t)]
    if missing:
        sys.exit(f"no {', '.join(map(str, missing))}")
    return 0


def test(pattern: str | None, prebuilt: bool = False) -> int:
    docker_platform, target = arch()
    if not prebuilt:
        test_inputs([a for a, (_, t) in FLATPAK_ARCHES.items() if t == target])
    missing = missing_test_inputs(target)
    if missing:
        sys.exit(f"--prebuilt finds no {', '.join(str(f.relative_to(ROOT)) for f in missing)}: run `run.py test-inputs` first")
    kexe_dirs = test_kexe_dirs(target)
    # an artifact keeps no file mode: a binary CI's `app-inputs` handed over arrives without its executable bit
    for kexe_dir in kexe_dirs.values():
        kexe = kexe_dir / "test.kexe"
        kexe.chmod(kexe.stat().st_mode | 0o111)
    build_image(docker_platform)
    # each module's binary in turn, the first red one's exit code the run's
    runner = repo_layout.MOBILE / "build" / "linux" / "test-modules.sh"
    runner.parent.mkdir(parents=True, exist_ok=True)
    runner.write_text("#!/bin/bash\n" + "".join(f'echo "+ {m}"; /veyq/bin/{m}/test.kexe "$@" || exit $?\n' for m in kexe_dirs))

    # the QR reader's fixtures (QrFixtures, read through VEYQ_REPO_ROOT at their repository path)
    qr_fixtures = "src/mobile/core/src/commonTest/resources/qr"
    # and the search's vectors (SearchVectorsTest, ADR 0146 decision 1), read the same way
    search_vectors = "contracts/platform/search/v1-vectors.json"
    # and the import's cases, the web's download and the manifest's schema (ImportFixtures, ZipReaderTest; ADR 0152 decision 4)
    export_contracts = "contracts/export"
    # and the web's format vectors (ShownVectorsTest, ADR 0154 decision 5), read the same way
    format_vectors = "src/mobile/words/format-vectors.json"
    # and the node tree's vectors (TreeVectorsTest), read the same way
    tree_vectors = "contracts/platform/tree/v1-vectors.json"
    # and the node plans' vectors (PlanVectorsTest), read the same way
    plan_vectors = "contracts/platform/plans/v1-vectors.json"
    # and the file facts' vectors (FileFactsVectorsTest), read the same way
    file_vectors = "contracts/platform/files/v1-vectors.json"
    mounts = {**{d: f"/veyq/bin/{m}" for m, d in kexe_dirs.items()}, runner: "/veyq/run/test-modules.sh",
              VECTORS: "/veyq/vectors.json", NORMALIZATION_TEST: "/veyq/NormalizationTest.txt",
              ROOT / qr_fixtures: f"/veyq/repo/{qr_fixtures}", ROOT / search_vectors: f"/veyq/repo/{search_vectors}",
              ROOT / export_contracts: f"/veyq/repo/{export_contracts}", ROOT / format_vectors: f"/veyq/repo/{format_vectors}",
              ROOT / tree_vectors: f"/veyq/repo/{tree_vectors}", ROOT / plan_vectors: f"/veyq/repo/{plan_vectors}",
              ROOT / file_vectors: f"/veyq/repo/{file_vectors}"}
    env = {"VEYQ_VECTORS": "/veyq/vectors.json", "VEYQ_NORMALIZATION_TEST": "/veyq/NormalizationTest.txt",
           "VEYQ_REPO_ROOT": "/veyq/repo", "TZ": ZONE}
    # WallClockTest's values are Berlin's. kotlinx-datetime reads the zone from /etc/localtime, glibc from TZ: both set.
    prelude = f"ln -sf /usr/share/zoneinfo/{ZONE} /etc/localtime; "
    filter_arg = " " + shlex.quote(f"--ktest_filter={pattern}") if pattern else ""
    script = prelude + SECRET_SERVICE.format(tests=f"bash /veyq/run/test-modules.sh{filter_arg}")
    if not pattern:
        # The store where none answers: libsecret there but no session bus, then libsecret moved away (the platform's).
        probe = "/veyq/bin/core/test.kexe --ktest_filter='*SecretServiceProbeTest*'"
        script += (f"; rc=$?; [ $rc -ne 0 ] && exit $rc; "
                   f"echo '+ the Secret Service probe without a session bus'; "
                   f"env -u DBUS_SESSION_BUS_ADDRESS VEYQ_SECRET_SERVICE=no-bus {probe} || exit $?; "
                   f"echo '+ the Secret Service probe with libsecret-1.so.0 moved away'; "
                   f"mkdir -p /tmp/away && mv /usr/lib64/libsecret-1.so.0* /tmp/away/ && "
                   f"VEYQ_SECRET_SERVICE=absent exec {probe}")
    # gnome-keyring-daemon drops its capabilities at start, which a container refuses unless it may lock memory
    return in_container(docker_platform, mounts, env, script, docker_args=["--cap-add=IPC_LOCK"])


# The Secret Service the core's LinuxDeviceKeys contract runs over (`DeviceKeysContract`): a
# session bus of its own, gnome-keyring-daemon on it with its login collection made and unlocked by a password drawn
# here, in the container, per run — a throwaway test value, never printed, never leaving the container. `{tests}` runs
# inside that session with VEYQ_SECRET_SERVICE=present; the script's exit code is its.
SECRET_SERVICE = (
    "mkdir -p -m 700 /tmp/xdg ~/.cache ~/.local/share/keyrings; export XDG_RUNTIME_DIR=/tmp/xdg; "
    "dbus-run-session -- env VEYQ_SECRET_SERVICE=present bash -c '"
    "head -c 32 /dev/urandom | base64 | tr -d \"\\n\" | gnome-keyring-daemon --unlock --components=secrets >/dev/null "
    "|| exit 70; exec \"$@\"' veyq {tests}"
)


def in_container(docker_platform: str, mounts: dict[Path, str], env: dict[str, str], script: str,
                 writable: dict[Path, str] | None = None, volumes: dict[str, str] | None = None,
                 docker_args: list[str] | None = None, secrets: dict[str, str] | None = None) -> int:
    """Runs bash [script] in the image, [mounts] read-only, [writable] read-write, [volumes] the named volumes; with
    VEYQ_LIVE, the dev origin reachable under its own name and the seeded principal's env file at /veyq/env. [secrets]
    reach the container by name only (`-e NAME`, the value in docker's own environment), so the printed command holds
    none of them — but the container's environment does: a pass never prints it (`env`, `docker inspect`), which once
    showed the dev principal's password and Secret Key (src/mobile/CLAUDE.md § Traps). Returns the script's exit code."""
    docker = ["docker", "run", "--rm", "--platform", docker_platform] + (docker_args or [])
    for k in secrets or {}:
        docker += ["-e", k]
    for host_path, container_path in (writable or {}).items():
        docker += ["-v", f"{host_path}:{container_path}"]
    for name, container_path in (volumes or {}).items():
        docker += ["-v", f"{name}:{container_path}"]
    prelude = ""
    live = os.environ.get("VEYQ_LIVE")
    if live:
        env_file = Path(os.environ.get("VEYQ_ENV_FILE", ROOT / ".run" / ".env"))
        if not env_file.exists():
            sys.exit(f"VEYQ_LIVE needs the seeded principal's {env_file} (VEYQ_ENV_FILE names another)")
        mounts = mounts | {env_file: "/veyq/env"}
        env = env | {"VEYQ_LIVE": live, "VEYQ_ENV_FILE": "/veyq/env"}
        # the origin, and the object store's public endpoint where a blob goes up or comes down (VEYQ_LIVE_STORE: a
        # dev instance's is Vite's origin, http://veyq.localhost:8081), each reachable under its own name
        hosts: set[str] = set()
        for address in [live, os.environ.get("VEYQ_LIVE_STORE")]:
            origin = urlsplit(address or "")
            if origin.hostname and (origin.hostname == "localhost" or origin.hostname.endswith(".localhost")):
                port = origin.port or (443 if origin.scheme == "https" else 80)
                if origin.hostname not in hosts:
                    docker += ["--add-host", f"{origin.hostname}:127.0.0.1"]
                    hosts.add(origin.hostname)
                prelude += (f"socat TCP-LISTEN:{port},bind=127.0.0.1,fork,reuseaddr TCP:host.docker.internal:{port} & "
                           f"for _ in $(seq 50); do (exec 3<>/dev/tcp/127.0.0.1/{port}) 2>/dev/null && break; sleep 0.1; done; ")
    for host_path, container_path in mounts.items():
        docker += ["-v", f"{host_path}:{container_path}:ro"]
    for k, v in env.items():
        docker += ["-e", f"{k}={v}"]
    docker += [IMAGE, "bash", "-c", prelude + script]
    print("+ " + " ".join(docker), flush=True)
    return subprocess.run(docker, env=os.environ | (secrets or {})).returncode


def bridge() -> int:
    """Links the bridge's release library, checks it defines exactly bridge.h's six functions and Kotlin/Native's own
    symbols, and runs `bridge/smoke/smoke.c` against it through bridge.h alone."""
    docker_platform, target = arch()
    run("./gradlew", f":bridge:linkReleaseShared{target}", cwd=repo_layout.MOBILE)
    lib_dir = repo_layout.MOBILE / "bridge" / "build" / "bin" / (target[0].lower() + target[1:]) / "releaseShared"
    if not (lib_dir / "libveyq.so").exists():
        sys.exit(f"no library at {lib_dir / 'libveyq.so'}")
    build_image(docker_platform)
    mounts = {lib_dir: "/veyq/lib", repo_layout.MOBILE / "bridge" / "bridge.h": "/veyq/include/bridge.h",
              repo_layout.MOBILE / "bridge" / "smoke": "/veyq/smoke"}
    # What the library may define: bridge.h's six, read from it, and Kotlin/Native's own — nothing of the static
    # libraries linked in (bridge/build.gradle.kts: --exclude-libs). A new K/N symbol is read and added here, never waved by.
    header = (repo_layout.MOBILE / "bridge" / "bridge.h").read_text()
    six = set(re.findall(r"\b(veyq_[a-z]+)\s*\(", re.sub(r"/\*.*?\*/", "", header, flags=re.S)))
    expected = sorted(six | KOTLIN_NATIVE_EXPORTS)
    script = ("set -e; "
              "echo '+ nm -D --defined-only libveyq.so'; "
              "nm -D --defined-only /veyq/lib/libveyq.so | awk '{print $3}' | LC_ALL=C sort > /tmp/defined; "
              "echo \"$(wc -l < /tmp/defined) symbols defined\"; "
              f"diff <(printf '%s\\n' {' '.join(expected)} | LC_ALL=C sort) /tmp/defined; "
              "echo 'the library defines bridge.h six functions and Kotlin/Native own symbols, nothing else'; "
              "gcc -std=c11 -Wall -Wextra -Werror -D_DEFAULT_SOURCE -I/veyq/include /veyq/smoke/smoke.c -L/veyq/lib -lveyq -lpthread -o /tmp/smoke; "
              "LD_LIBRARY_PATH=/veyq/lib /tmp/smoke")
    return in_container(docker_platform, mounts, {}, script)


def generate(target: str) -> Path:
    """Writes everything the app is built from but does not keep, and links the bridge's library; returns its
    directory."""
    run("./gradlew", ":bridge:generateBindings", f":bridge:linkDebugShared{target}", ":bridge:jvmTest",
        "--tests", "*WireTest*", cwd=repo_layout.MOBILE)
    run(sys.executable, str(WORDS / "generate_words.py"), "--gettext", str(GENERATED / "linux" / "words"))
    run(sys.executable, str(THEME / "generate_theme.py"), "--gtk", str(GENERATED / "linux" / "theme"))
    lib_dir = repo_layout.MOBILE / "bridge" / "build" / "bin" / (target[0].lower() + target[1:]) / "debugShared"
    if not (lib_dir / "libveyq.so").exists():
        sys.exit(f"no library at {lib_dir / 'libveyq.so'}")
    return lib_dir


def cargo(docker_platform: str, lib_dir: Path, script: str, docker_args: list[str] | None = None,
          env: dict[str, str] | None = None, secrets: dict[str, str] | None = None,
          volumes: dict[str, str] | None = None) -> int:
    """Runs [script] in the package's directory in the container, with what build.rs and the tests read."""
    mounts = {repo_layout.MOBILE_LINUX: "/veyq/src", GENERATED: "/veyq/generated", lib_dir: "/veyq/lib",
              WORDS / "words.json": "/veyq/words.json"}
    base = {"CARGO_HOME": "/veyq/cargo", "CARGO_TARGET_DIR": "/veyq/target", "VEYQ_GENERATED": "/veyq/generated",
            "VEYQ_LIB_DIR": "/veyq/lib", "LD_LIBRARY_PATH": "/veyq/lib", "VEYQ_SAMPLES": "/veyq/generated/bridge/samples",
            "VEYQ_WORDS_JSON": "/veyq/words.json", "VEYQ_APP_ID": APP_ID, "LANG": "en_US.UTF-8"}
    return in_container(docker_platform, mounts, base | (env or {}), f"set -e; cd /veyq/src; {script}",
                        volumes=CARGO_VOLUMES | (volumes or {}), docker_args=docker_args, secrets=secrets)


def build() -> int:
    """The app proved in the SDK it ships on (ADR 0139 decision 4): the Flatpak's modules before the app built by
    flatpak-builder (`--stop-at`, from its cache), then clippy, the tests and the build in that build directory."""
    docker_platform, target = arch()
    flatpak_arch = {"linux/arm64": "aarch64", "linux/amd64": "x86_64"}[docker_platform]
    lib_dir = generate(target)
    write_manifest(*tree_version())
    env = {"CARGO_HOME": "/veyq/cargo", "CARGO_TARGET_DIR": "/veyq/target", "VEYQ_GENERATED": "/veyq/generated",
           "VEYQ_LIB_DIR": "/veyq/lib", "LD_LIBRARY_PATH": "/veyq/lib:/app/lib",
           "VEYQ_SAMPLES": "/veyq/generated/bridge/samples", "VEYQ_WORDS_JSON": "/veyq/words.json",
           "VEYQ_APP_ID": APP_ID, "LANG": "en_US.UTF-8", "VEYQ_KIT_FIXTURES": "/veyq/kit-fixtures",
           "PKG_CONFIG_PATH": "/app/lib/pkgconfig:/app/share/pkgconfig",
           "PATH": "/usr/lib/sdk/rust-stable/bin:/app/bin:/usr/bin"}
    sandboxed = " ".join(f"--env={k}={v}" for k, v in env.items())
    skips = " ".join(f"--skip {s}" for s in SANDBOX_SKIPS)
    script = (
        "cd /veyq/mobile/build/linux/flatpak; "
        f"flatpak-builder --user --arch={flatpak_arch} --disable-rofiles-fuse --disable-updates --force-clean "
        f"--state-dir=/veyq/state/flatpak-builder --stop-at=veyq /veyq/state/sdk-dir {APP_ID}.yml; "
        f"flatpak build --share=network --filesystem=/veyq {sandboxed} /veyq/state/sdk-dir bash -c "
        + shlex.quote("set -e; cd /veyq/src; cargo clippy --locked --all-targets -- -D warnings; "
                      f"cargo test --locked -- {skips}; cargo build --locked"))
    rc = in_runtime(flatpak_arch, script, [
        "-v", f"veyq-flatpak-state-{flatpak_arch}-{CHECKOUT}:/veyq/state",
        "-v", "veyq-flatpak-cargo:/veyq/cargo", "-v", f"veyq-flatpak-target-{flatpak_arch}-{CHECKOUT}:/veyq/target",
        "-v", f"{repo_layout.MOBILE}:/veyq/mobile:ro", "-v", f"{FLATPAK_OUT}:/veyq/mobile/build/linux/flatpak",
        "-v", f"{repo_layout.MOBILE_LINUX}:/veyq/src:ro", "-v", f"{GENERATED}:/veyq/generated:ro",
        "-v", f"{lib_dir}:/veyq/lib:ro", "-v", f"{WORDS / 'words.json'}:/veyq/words.json:ro",
        # the web's Emergency Kit fixtures, which kit_drawing's test draws and hands to the core
        "-v", f"{ROOT / KIT_FIXTURES}:/veyq/kit-fixtures:ro"])
    if rc != 0:
        return rc
    # what the SDK's sandbox cannot run (glycin needs a portal there) runs where a pass runs the app, outside any sandbox
    build_image(docker_platform)
    exact = " ".join(shlex.quote(s) for s in SANDBOX_SKIPS)
    return cargo(docker_platform, lib_dir, f"cargo test --locked -- --exact {exact}")


def dev_principal(unlock: bool = False, fill: bool = False) -> dict[str, str]:
    """The seeded dev principal as the debug build's VEYQ_DEV_* (DevLogin.swift's names): the server is VEYQ_LIVE's
    origin, the rest the env file's (VEYQ_ENV_FILE, else `.run/.env`). With [unlock] the password alone, as
    VEYQ_DEV_UNLOCK_PASSWORD (Android's `--unlock`); with [fill] the app fills its sign-in and leaves the
    button to the pass (VEYQ_DEV_FILL). Nothing of it is printed."""
    live = os.environ.get("VEYQ_LIVE")
    if not live:
        sys.exit("--devlogin needs VEYQ_LIVE (the dev origin the app signs in to, e.g. http://veyq.localhost:8080)")
    env_file = Path(os.environ.get("VEYQ_ENV_FILE", ROOT / ".run" / ".env"))
    if not env_file.exists():
        sys.exit(f"--devlogin needs the seeded principal's {env_file} (VEYQ_ENV_FILE names another)")
    values: dict[str, str] = {}
    for line in env_file.read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            values[k.strip()] = v.strip().strip('"').strip("'")
    if unlock:
        return {"VEYQ_DEV_UNLOCK_PASSWORD": values["VEYQ_PASSWORD"]}
    principal = {"VEYQ_DEV_SERVER": live, "VEYQ_DEV_EMAIL": values["VEYQ_EMAIL"], "VEYQ_DEV_PASSWORD": values["VEYQ_PASSWORD"],
                 "VEYQ_DEV_SECRET_KEY": values["VEYQ_SECRET_KEY"]}
    return principal | ({"VEYQ_DEV_FILL": "1"} if fill else {})


# What the app keeps between two `run`s: the home's data directory
# as a named volume — the device store (`veyq/device-store.json`), gnome-keyring's login collection (`keyrings/`) and
# the throwaway password that collection opens with (`veyq-run/keyring`, drawn in the container at the first start,
# never printed). A test value beside the keyring it opens: this keyring holds a dev instance's trust and nothing else.
# `docker volume rm veyq-linux-home` starts from nothing.
HOME_VOLUME = "veyq-linux-home"

# The app under a session bus of its own with gnome-keyring-daemon on it, whose login collection the kept password makes
# or unlocks, so a second start finds the keys the first one stored.
APP_UNDER_SECRET_SERVICE = (
    "mkdir -p -m 700 ~/.cache ~/.local/share/keyrings ~/.local/share/veyq-run; "
    "[ -s ~/.local/share/veyq-run/keyring ] || (umask 077; head -c 32 /dev/urandom | base64 | tr -d '\\n' "
    "> ~/.local/share/veyq-run/keyring); "
    "exec dbus-run-session -- bash -c 'gnome-keyring-daemon --unlock --components=secrets "
    "< ~/.local/share/veyq-run/keyring >/dev/null || exit 70; exec \"$@\"' veyq /veyq/target/debug/veyq"
)


def run_app(port: int, devlogin: bool, unlock: bool = False, fill: bool = False, secret_service: bool = True,
            home: str = HOME_VOLUME) -> int:
    secrets = dev_principal(unlock, fill) if devlogin else None
    docker_platform, target = arch()
    lib_dir = generate(target)
    build_image(docker_platform)
    inner = 8080 + BROADWAY_DISPLAY
    env = {k: os.environ[k] for k in ("LANGUAGE", "ADW_DEBUG_COLOR_SCHEME") if k in os.environ}
    # the app's locale (its money, its dates): VEYQ_LOCALE, never the host's own LANG, which says nothing of the image's
    if "VEYQ_LOCALE" in os.environ:
        env["LANG"] = os.environ["VEYQ_LOCALE"]
    env |= {"GDK_BACKEND": "broadway", "BROADWAY_DISPLAY": f":{BROADWAY_DISPLAY}", "XDG_RUNTIME_DIR": "/tmp/xdg"}
    # a debug build's QR harness (dev_scan.rs) and its test camera, an image on this host mounted read-only
    docker_args = ["-p", f"127.0.0.1:{port}:{inner}"]
    if os.environ.get("VEYQ_DEV_SCAN"):
        env["VEYQ_DEV_SCAN"] = os.environ["VEYQ_DEV_SCAN"]
    # the account Settings' harness (dev_settings.rs)
    if os.environ.get("VEYQ_DEV_SETTINGS"):
        env["VEYQ_DEV_SETTINGS"] = os.environ["VEYQ_DEV_SETTINGS"]
    # a third party a pass must not reach (the real range service an instance names): refused at once in the container
    for host in filter(None, (h.strip() for h in os.environ.get("VEYQ_UNREACHABLE", "").split(","))):
        docker_args += ["--add-host", f"{host}:127.0.0.1"]
    if os.environ.get("VEYQ_DEV_CAMERA"):
        image = Path(os.environ["VEYQ_DEV_CAMERA"]).resolve()
        if not image.is_file():
            sys.exit(f"VEYQ_DEV_CAMERA names no file: {image}")
        docker_args += ["-v", f"{image}:/veyq/dev-camera{image.suffix}:ro"]
        env["VEYQ_DEV_CAMERA"] = f"/veyq/dev-camera{image.suffix}"
    print(f"veyq: the app draws into http://127.0.0.1:{port} once cargo built it", flush=True)
    return cargo(docker_platform, lib_dir,
                 "cargo build --locked; mkdir -p -m 700 /tmp/xdg; "
                 f"gtk4-broadwayd --address 0.0.0.0 :{BROADWAY_DISPLAY} & "
                 # ready once its socket is there (a bare TCP probe makes it log "Error reading request lines")
                 "for _ in $(seq 50); do ls /tmp/xdg/broadway*.socket >/dev/null 2>&1 && break; sleep 0.1; done; "
                 + (APP_UNDER_SECRET_SERVICE if secret_service else "exec /veyq/target/debug/veyq"),
                 # gnome-keyring-daemon drops its capabilities at start, which a container refuses unless it may lock memory
                 docker_args=docker_args + (["--cap-add=IPC_LOCK"] if secret_service else []),
                 env=env, secrets=secrets, volumes={home: "/root/.local/share"} if secret_service else None)


def lock() -> int:
    docker_platform, _ = arch()
    build_image(docker_platform)
    return in_container(docker_platform, {}, {"CARGO_HOME": "/veyq/cargo"}, "set -e; cd /veyq/src; cargo update",
                        writable={repo_layout.MOBILE_LINUX: "/veyq/src"}, volumes={"veyq-linux-cargo": "/veyq/cargo"})


# ---- the Flatpak (ADR 0139) ----

FLATPAK = HERE / "flatpak"
# the local tag of flatpak/Containerfile's build (pinned there by digest), as IMAGE is the Containerfile's beside it
FLATPAK_BUILDER_TAG = "veyq-flatpak-builder"
FLATPAK_OUT = repo_layout.MOBILE / "build" / "linux" / "flatpak"
FLATHUB = "https://dl.flathub.org/repo/flathub.flatpakrepo"
# The web's Emergency Kit fixtures (fake data): kit_drawing's test reads them through VEYQ_KIT_FIXTURES.
KIT_FIXTURES = "src/web/src/features/account/emergencyKit/__fixtures__"
# Flatpak's architecture -> (Docker platform, Kotlin/Native target)
FLATPAK_ARCHES = {"aarch64": ("linux/arm64", "LinuxArm64"), "x86_64": ("linux/amd64", "LinuxX64")}
# The refs a build installs; their commits are runtime.pins'. org.flatpak.Builder carries flatpak-builder-lint.
FLATPAK_REFS = ("org.gnome.Platform", "org.gnome.Sdk", "org.freedesktop.Sdk.Extension.rust-stable", "org.flatpak.Builder")
# The one test a Flatpak's build cannot run: inside a Flatpak, glycin runs its loader through `flatpak-spawn
# --sandbox`, for which a build's sandbox has no portal ("Loader process exited early", measured 2026-11-13). The
# installed app's sandbox has one: the Linux pass runs it there (ADR 0123's Consequences, the Linux app), and
# `run.py run`'s Fedora container runs it outside any sandbox.
# A picked medium's tiers (media_tiers) decode and encode through gdk-pixbuf, which goes through glycin for both in the
# GNOME 51 runtime — its JPEG saver too (measured 2026-11-13: "Loader process exited early" for a save from raw pixels in
# that sandbox, where GTK's own loader still decodes a JPEG): those run in the Fedora 44 container after the SDK's pass.
SANDBOX_SKIPS = ("core_ui::avatars::tests::a_picture_as_the_web_writes_it_decodes",
                 "core_ui::media_tiers::tests::a_photo_is_turned_by_its_exif_and_fitted_to_the_cores_edges",
                 "core_ui::media_tiers::tests::a_videos_poster_is_its_frame_at_the_cores_moment",
                 "core_ui::media_tiers::tests::a_file_that_does_not_decode_has_no_tiers",
                 "core_ui::media_tiers::tests::the_reply_packs_the_preview_then_the_thumbnail",
                 # an SVG through glycin's sandboxed librsvg, held to inbox #1059's threat model (file_page.rs)
                 "apps::depot::file_page::tests::an_svg_draws_with_nothing_in_it_run_or_fetched")


def runtime_pins(flatpak_arch: str) -> dict[str, str]:
    """runtime.pins' lines for [flatpak_arch]: `<id>//<branch>` -> commit."""
    pins = {}
    for line in (FLATPAK / "runtime.pins").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            ref, commit = line.split()
            ident, ref_arch, branch = ref.split("/")
            if ref_arch == flatpak_arch:
                pins[f"{ident}//{branch}"] = commit
    missing = [r for r in FLATPAK_REFS if not any(p.startswith(r + "//") for p in pins)]
    if missing:
        sys.exit(f"runtime.pins names no {flatpak_arch} commit for {', '.join(missing)}")
    return pins


def tree_version() -> tuple[str, str]:
    """The tree's `<major>.<minor>` as the pipeline counts it (release_assets.tree_release, the one count) and the
    commit's date, `YYYY-MM-DD`: what the metainfo's one release says, the same for the same commit."""
    import release_assets  # noqa: E402 - scripts/, on sys.path above
    date = subprocess.run(["git", "log", "-1", "--format=%cs", "HEAD"], cwd=ROOT, capture_output=True, text=True,
                          check=True).stdout.strip()
    return release_assets.tree_release(ROOT), date


def release_library(target: str) -> Path:
    lower = target[0].lower() + target[1:]
    return repo_layout.MOBILE / "bridge" / "build" / "bin" / lower / "releaseShared" / "libveyq.so"


# What `inputs` writes beside the libraries, each a directory `flatpak --prebuilt` refuses to go without.
GENERATED_INPUTS = (GENERATED / "bridge" / "samples", GENERATED / "linux" / "words", GENERATED / "linux" / "theme")


def inputs(flatpak_arches: list[str]) -> int:
    """The core and everything generated, outside the sandbox (decision 4): the release library of each of
    [flatpak_arches], the bridge's Rust and the samples its round trip reads, the words, the theme."""
    targets = [FLATPAK_ARCHES[a][1] for a in flatpak_arches]
    run("./gradlew", ":bridge:generateBindings", *(f":bridge:linkReleaseShared{t}" for t in targets), ":bridge:jvmTest",
        "--tests", "*WireTest*", cwd=repo_layout.MOBILE)
    run(sys.executable, str(WORDS / "generate_words.py"), "--gettext", str(GENERATED / "linux" / "words"))
    run(sys.executable, str(THEME / "generate_theme.py"), "--gtk", str(GENERATED / "linux" / "theme"))
    for target in targets:
        if not release_library(target).exists():
            sys.exit(f"no library at {release_library(target)}")
    return 0


def flatpak(flatpak_arch: str, prebuilt: bool = False, smoke: bool = False) -> int:
    docker_platform, target = FLATPAK_ARCHES[flatpak_arch]
    runtime_pins(flatpak_arch)  # refuses a pin file that lacks one, before the long steps
    version, date = tree_version()
    lib = release_library(target)
    if not prebuilt:
        inputs([flatpak_arch])
    missing = [p for p in (lib, *GENERATED_INPUTS) if not p.exists()]
    if missing:
        sys.exit(f"--prebuilt finds no {', '.join(str(p.relative_to(ROOT)) for p in missing)}: run `run.py inputs` first"
                 if prebuilt else f"no {', '.join(map(str, missing))}")

    # The inputs the manifest's `type: dir` sources name, written anew each run.
    staged, vendor = FLATPAK_OUT / "inputs", FLATPAK_OUT / "vendor"
    shutil.rmtree(staged, ignore_errors=True)
    (staged / "lib").mkdir(parents=True)
    shutil.copy2(lib, staged / "lib" / "libveyq.so")
    shutil.copytree(GENERATED / "bridge", staged / "generated" / "bridge")
    shutil.copytree(GENERATED / "linux", staged / "generated" / "linux")
    shutil.copy2(WORDS / "words.json", staged / "words.json")
    shutil.copytree(ROOT / KIT_FIXTURES, staged / "kit-fixtures")
    write_manifest(version, date)
    # The crates, offline: `cargo vendor --locked` in the development image (cargo is there, not in the builder's),
    # each crate checked against Cargo.lock's checksum. Every crate is crates.io's (Cargo.lock has no other source),
    # so the config points that one source at the vendored copy, `/run/build/veyq/vendor` in the SDK's build.
    build_image(docker_platform)
    shutil.rmtree(vendor, ignore_errors=True)
    rc = in_container(docker_platform, {repo_layout.MOBILE_LINUX: "/veyq/src"}, {"CARGO_HOME": "/veyq/cargo"},
                      "set -e; cd /veyq/src; cargo vendor --locked --quiet /veyq/out/vendor",
                      writable={FLATPAK_OUT: "/veyq/out"}, volumes={"veyq-linux-cargo": "/veyq/cargo"})
    if rc:
        return rc
    sources = set(re.findall(r'^source = "([^"]+)"', (HERE / "Cargo.lock").read_text(), re.MULTILINE))
    if sources != {"registry+https://github.com/rust-lang/crates.io-index"}:
        sys.exit(f"Cargo.lock names sources beside crates.io, which the vendored config does not replace: {sources}")
    (staged / "cargo-config.toml").write_text(
        '[source.crates-io]\nreplace-with = "vendored-sources"\n\n'
        '[source.vendored-sources]\ndirectory = "/run/build/veyq/vendor"\n')

    # The build, in the builder's image: the pinned runtime, flatpak-builder, the checks, the bundle.
    bundle = f"veyq-{version}-{flatpak_arch}.flatpak"
    # red on anything but lint-exceptions.json's, each named there with why
    lint = ("flatpak run --user --filesystem=/veyq --command=flatpak-builder-lint org.flatpak.Builder --exceptions "
            "--user-exceptions /veyq/mobile/build/linux/flatpak/lint-exceptions.json")
    script = (
        "cd /veyq/mobile/build/linux/flatpak; "  # the manifest's paths are relative to where it lies
        f"appstreamcli validate --no-net --pedantic --explain inputs/{APP_ID}.metainfo.xml; "
        f"flatpak-builder --user --arch={flatpak_arch} --disable-rofiles-fuse --disable-updates --force-clean "
        # the state, the build directory and the repository share the volume (flatpak-builder wants one filesystem,
        # and a host directory under Docker Desktop is a slow one); only the bundle lands in the tree's build/
        f"--state-dir=/veyq/state/flatpak-builder --default-branch=stable --repo=/veyq/state/repo "
        f"/veyq/state/build-dir {APP_ID}.yml; "
        f"echo '+ flatpak-builder-lint manifest'; {lint} manifest {APP_ID}.yml; "
        f"echo '+ flatpak-builder-lint repo'; {lint} repo /veyq/state/repo; "
        f"flatpak build-bundle --arch={flatpak_arch} --runtime-repo={FLATHUB} /veyq/state/repo {bundle} {APP_ID} stable; "
        f"ls -l {bundle}"
    )
    if smoke:
        script += "; " + SMOKE.format(arch=flatpak_arch, bundle=bundle, app=APP_ID, line=SMOKE_SIGN_IN)
    rc = in_runtime(flatpak_arch, script,
                    ["-v", f"veyq-flatpak-state-{flatpak_arch}-{CHECKOUT}:/veyq/state",
                     "-v", f"{repo_layout.MOBILE}:/veyq/mobile:ro",
                     "-v", f"{FLATPAK_OUT}:/veyq/mobile/build/linux/flatpak"])
    if rc == 0:
        print(f"veyq: {FLATPAK_OUT / bundle}", flush=True)
    return rc


# What the app logs once it shows its sign-in (main.rs, the session's gate): the smoke's end, as Windows' is.
SMOKE_SIGN_IN = "veyq: session SignIn"
# The installed bundle started as a person would get it, but headless: inside its own sandbox a session bus of its own
# (GApplication registers on one) and the runtime's gtk4-broadwayd for a display, so nothing of the container's is
# drawn on. Red unless its log says SMOKE_SIGN_IN within 60 s; then uninstalled with its data, red if any is left.
SMOKE = (
    "echo '+ the smoke: the bundle installed, started headless to its sign-in, uninstalled'; "
    "flatpak install --user -y --noninteractive --reinstall --arch={arch} {bundle}; "
    "flatpak run --user --arch={arch} --command=bash {app} -c "
    + shlex.quote("exec dbus-run-session -- bash -c 'gtk4-broadwayd :5 & "
                  "for _ in $(seq 50); do ls \"$XDG_RUNTIME_DIR\"/broadway*.socket >/dev/null 2>&1 && break; sleep 0.1; done; "
                  "GDK_BACKEND=broadway BROADWAY_DISPLAY=:5 exec veyq'")
    + " > /tmp/smoke.log 2>&1 & app=$!; "
    "seen=0; for _ in $(seq 120); do "
    "if grep -qF '{line}' /tmp/smoke.log; then seen=1; break; fi; kill -0 $app 2>/dev/null || break; sleep 0.5; done; "
    "kill $app 2>/dev/null || true; wait $app 2>/dev/null || true; "
    "echo '--- the app'; cat /tmp/smoke.log; "
    "[ $seen = 1 ] || {{ echo 'veyq: the installed bundle did not reach its sign-in in 60 s'; exit 1; }}; "
    "dbus-run-session -- flatpak uninstall --user -y --noninteractive --delete-data {app}; "
    "if flatpak info --user {app} >/dev/null 2>&1 || [ -e ~/.var/app/{app} ]; then "
    "echo 'veyq: the bundle is still installed, or its data left behind'; exit 1; fi; "
    "echo 'veyq: the installed bundle showed its sign-in ({line}) and is uninstalled, nothing left'"
)


def write_manifest(version: str, date: str) -> None:
    """The manifest, the metainfo and the linter's exceptions, filled from `flatpak/` with the id, the version, the
    commit's date and the tests a build's sandbox skips, into src/mobile/build/linux/flatpak/."""
    fill = {"@APP_ID@": APP_ID, "@VERSION@": version, "@DATE@": date,
            "@SANDBOX_SKIPS@": " ".join(f"--skip {s}" for s in SANDBOX_SKIPS)}

    def filled(text: str) -> str:
        for k, v in fill.items():
            text = text.replace(k, v)
        return text

    (FLATPAK_OUT / "inputs").mkdir(parents=True, exist_ok=True)
    # the template's summary and description are the catalog's, in English and German (generate_words.py --metainfo)
    metainfo = FLATPAK_OUT / "inputs" / f"{APP_ID}.metainfo.xml"
    run(sys.executable, str(WORDS / "generate_words.py"), "--metainfo", str(FLATPAK / "metainfo.xml"), str(metainfo))
    metainfo.write_text(filled(metainfo.read_text()))
    (FLATPAK_OUT / f"{APP_ID}.yml").write_text(filled((FLATPAK / "manifest.yml").read_text()))
    # flatpak-builder-lint's exceptions, each with its reason, keyed by the id as the linter reads them
    exceptions = json.loads((FLATPAK / "lint-exceptions.json").read_text())
    (FLATPAK_OUT / "lint-exceptions.json").write_text(json.dumps({APP_ID: exceptions}, indent=2))


def in_runtime(flatpak_arch: str, script: str, docker_args: list[str]) -> int:
    """Runs bash [script] in the builder's image (privileged: bubblewrap) after it installed the runtime, the SDK, the
    Rust extension and flatpak-builder-lint from Flathub and moved each to its runtime.pins commit, all kept in the
    volume `veyq-flatpak-<arch>`. Returns the script's exit code."""
    docker_platform, _ = FLATPAK_ARCHES[flatpak_arch]
    pins = runtime_pins(flatpak_arch)
    run("docker", "build", "--platform", docker_platform, "-t", FLATPAK_BUILDER_TAG, "-f", str(FLATPAK / "Containerfile"),
        str(FLATPAK))
    flags = f"--user -y --noninteractive --arch={flatpak_arch}"
    moves = "; ".join(
        f'[ "$(flatpak info --user --arch={flatpak_arch} --show-commit {ref})" = {commit} ] '
        f"|| flatpak update {flags} --commit={commit} {ref}" for ref, commit in pins.items())
    prelude = (
        "set -e; "
        f"flatpak remote-add --user --if-not-exists flathub {FLATHUB}; "
        f"flatpak install {flags} flathub {' '.join(pins)}; "
        f"{moves}; "
        "echo '+ the pinned commits'; "
        f"flatpak list --user --arch={flatpak_arch} --columns=ref,active; ")
    docker = (["docker", "run", "--rm", "--privileged", "--platform", docker_platform,
               "-v", f"veyq-flatpak-{flatpak_arch}:/root/.local/share/flatpak"] + docker_args
              + [FLATPAK_BUILDER_TAG, "bash", "-c", prelude + script])
    print("+ " + " ".join(docker), flush=True)
    return subprocess.run(docker).returncode


def arch() -> tuple[str, str]:
    try:
        return ARCHES[platform.machine()]
    except KeyError:
        sys.exit(f"no Linux target for this host's architecture ({platform.machine()})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    verbs = parser.add_subparsers(dest="verb", required=True)
    t = verbs.add_parser("test", help="link the core's Linux test binary and run it in the container")
    t.add_argument("--tests", help="Kotlin/Native --ktest_filter pattern, e.g. '*LiveSignInTest*'")
    t.add_argument("--prebuilt", action="store_true", help="run the test binaries `test-inputs` left in this tree")
    ti = verbs.add_parser("test-inputs", help="link every core module's Linux test binaries, alone (CI's app-inputs)")
    ti.add_argument("--arch", choices=sorted(FLATPAK_ARCHES), action="append", help="repeatable; default: both")
    verbs.add_parser("bridge", help="link the bridge's library, check what it exports and run the C smoke against it")
    verbs.add_parser("build", help="generate, then cargo clippy, test and build the app in the container")
    r = verbs.add_parser("run", help="build the app and run it under gtk4-broadwayd, drawn into a browser")
    r.add_argument("--port", type=int, default=8095, help="the port on 127.0.0.1 the browser opens (default 8095)")
    r.add_argument("--devlogin", action="store_true", help="sign the debug build in as the seeded principal (needs VEYQ_LIVE)")
    r.add_argument("--fill", action="store_true", help="with --devlogin: only fill the sign-in; the pass presses it")
    r.add_argument("--unlock", action="store_true", help="with --devlogin: the password alone, for a password-trusted unlock")
    r.add_argument("--no-secret-service", action="store_true",
                   help="no session bus and no kept home: nothing is kept, every start signs in")
    r.add_argument("--home", default=HOME_VOLUME, metavar="VOLUME",
                   help=f"the named volume that keeps the home's data between runs (default {HOME_VOLUME})")
    verbs.add_parser("lock", help="cargo update: write Cargo.lock anew")
    f = verbs.add_parser("flatpak", help="build the Flatpak bundle in the pinned GNOME SDK (ADR 0139)")
    f.add_argument("--arch", choices=sorted(FLATPAK_ARCHES), help="default: this host's architecture")
    f.add_argument("--prebuilt", action="store_true", help="take the library and sources `inputs` left in this tree")
    f.add_argument("--smoke", action="store_true", help="install the bundle, start it headless to its sign-in, uninstall")
    i = verbs.add_parser("inputs", help="the release libraries and the generated sources, alone (CI's app-inputs)")
    i.add_argument("--arch", choices=sorted(FLATPAK_ARCHES), action="append", help="repeatable; default: both")
    args = parser.parse_args()
    if args.verb == "inputs":
        sys.exit(inputs(args.arch or sorted(FLATPAK_ARCHES)))
    if args.verb == "build":
        sys.exit(build())
    if args.verb == "run":
        if (args.fill or args.unlock) and not args.devlogin:
            sys.exit("--fill and --unlock go with --devlogin")
        if args.no_secret_service and args.home != HOME_VOLUME:
            sys.exit("--home keeps what the Secret Service stores: it does not go with --no-secret-service")
        sys.exit(run_app(args.port, args.devlogin, args.unlock, args.fill, not args.no_secret_service, args.home))
    if args.verb == "lock":
        sys.exit(lock())
    if args.verb == "flatpak":
        sys.exit(flatpak(args.arch or {"linux/arm64": "aarch64", "linux/amd64": "x86_64"}[arch()[0]], args.prebuilt,
                         args.smoke))
    if args.verb == "test":
        sys.exit(test(args.tests, args.prebuilt))
    if args.verb == "test-inputs":
        sys.exit(test_inputs(args.arch or sorted(FLATPAK_ARCHES)))
    if args.verb == "bridge":
        sys.exit(bridge())


if __name__ == "__main__":
    main()
