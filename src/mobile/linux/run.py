#!/usr/bin/env python3
"""The one entry point for everything Linux of the veyq app (ADR 0123's Consequences, the Linux app): it builds the Fedora 44
image of `Containerfile` beside it and runs what a verb names in it. Everything Kotlin is cross-compiled on this host
by Gradle; only running it needs Linux.

Verbs:
  test [--tests <pattern>]   links the core's Linux test binary for this host's architecture
                             (`:core:linkDebugTest<Target>`) and runs it in the container: the conformance suite over
                             libsodium, utf8proc's NormalizationTest, the wall clock in Europe/Berlin, and — with
                             VEYQ_LIVE set — the live sign-in. `--tests` is Kotlin/Native's `--ktest_filter` pattern
                             (`*LiveSignInTest*`). Its exit code is the test binary's.
  bridge                     links the bridge's release library (`:bridge:linkReleaseShared<Target>`), checks with
                             `nm -D` that it defines exactly bridge.h's six functions and Kotlin/Native's own
                             symbols (none of the static libraries linked in), and builds and runs
                             `bridge/smoke/smoke.c` against it through bridge.h alone; with VEYQ_LIVE set the smoke
                             also signs in as the seeded principal over the library's own HTTP client.
  build                      generates what the app is built from but does not keep — the bridge's Rust
                             (`:bridge:generateBindings`) and the samples its round trip reads (`WireTest`), the words'
                             gettext catalogs and Rust (`generate_words.py --gettext`), the theme
                             (`generate_theme.py --gtk`) — links the bridge's library, and runs in the container
                             `cargo clippy --locked --all-targets -- -D warnings`, `cargo test --locked` and
                             `cargo build --locked`. Its exit code is cargo's.
  run [--port N] [--devlogin] `build`'s generation and `cargo build`, then the app under `gtk4-broadwayd`, drawn into a
                             browser at http://127.0.0.1:N (default 8095; published on 127.0.0.1 only). LANGUAGE and
                             ADW_DEBUG_COLOR_SCHEME, when set, reach the app, VEYQ_LOCALE as its LANG (default
                             en_US.UTF-8; `VEYQ_LOCALE=de_DE.UTF-8 LANGUAGE=de` for German words and German
                             money). With VEYQ_LIVE the app reaches the dev origin under its own name (as `test`
                             does); `--devlogin` (needs VEYQ_LIVE) hands the debug build the seeded principal of the
                             env file as VEYQ_DEV_*, and it signs in through its own sign-in — the values go to
                             `docker run` through its environment, never on its command line.
  lock                       `cargo update`: writes Cargo.lock anew from Cargo.toml (the one way it changes).

cargo's registry and target directory live in the Docker volumes `veyq-linux-cargo` and `veyq-linux-target`, so a
rebuild is incremental; `docker volume rm` both to start clean.

Live runs: `VEYQ_LIVE=http://veyq.localhost:8080 run.py test --tests '*LiveSignInTest*'` (or `run.py bridge`) signs in
as the seeded principal of `.run/.env` (VEYQ_ENV_FILE names another file, e.g. the main checkout's from a worktree).
The backend picks the tenant from the Host header, so the container keeps the origin's name: a `*.localhost` origin
resolves to the container's own loopback there, where socat forwards its port to the host (`host.docker.internal`),
and every tenant of the account (each a `*.localhost` name on that port) arrives under its own name.

Docker must answer `docker build` / `docker run`; the image is `veyq-linux-dev`, rebuilt from its layer cache each run.
"""
from __future__ import annotations

import argparse
import os
import platform
import re
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
import repo_layout  # noqa: E402

HERE = repo_layout.MOBILE_LINUX
IMAGE = "veyq-linux-dev"
# What Kotlin/Native defines in a shared library beside its @CName functions: its C API's entry (`lib<name>_symbols`),
# two of its runtime's, and the ELF init/fini (read with `nm -D --defined-only` on 2026-11-10, Kotlin 2.4.21).
KOTLIN_NATIVE_EXPORTS = {"libveyq_symbols", "IsInstance", "Konan_cxa_demangle", "__cxa_demangle", "_init", "_fini"}
ZONE = "Europe/Berlin"
VECTORS = ROOT / "contracts" / "crypto" / "vectors" / "v1.json"
NORMALIZATION_TEST = repo_layout.MOBILE / "build" / "utf8proc" / "NormalizationTest.txt"
GENERATED = repo_layout.MOBILE / "build" / "generated"
WORDS = repo_layout.MOBILE / "words"
THEME = repo_layout.MOBILE / "theme"
# cargo's registry and its target, kept across runs (named volumes: nothing of them lands in the tree)
CARGO_VOLUMES = {"veyq-linux-cargo": "/veyq/cargo", "veyq-linux-target": "/veyq/target"}
BROADWAY_DISPLAY = 5  # gtk4-broadwayd serves display :N on port 8080 + N
# This host's architecture -> (Docker platform, Kotlin/Native target as Gradle names it)
ARCHES = {"arm64": ("linux/arm64", "LinuxArm64"), "aarch64": ("linux/arm64", "LinuxArm64"),
          "x86_64": ("linux/amd64", "LinuxX64"), "AMD64": ("linux/amd64", "LinuxX64")}


def run(*args: str, cwd: Path | None = None) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)


def build_image(docker_platform: str) -> None:
    run("docker", "build", "--platform", docker_platform, "-t", IMAGE, "-f", str(HERE / "Containerfile"), str(HERE))


def test(pattern: str | None) -> int:
    docker_platform, target = arch()
    run("./gradlew", f":core:linkDebugTest{target}", cwd=repo_layout.MOBILE)
    kexe_dir = repo_layout.MOBILE / "core" / "build" / "bin" / (target[0].lower() + target[1:]) / "debugTest"
    if not (kexe_dir / "test.kexe").exists():
        sys.exit(f"no test binary at {kexe_dir / 'test.kexe'}")
    build_image(docker_platform)

    mounts = {kexe_dir: "/veyq/bin", VECTORS: "/veyq/vectors.json", NORMALIZATION_TEST: "/veyq/NormalizationTest.txt"}
    env = {"VEYQ_VECTORS": "/veyq/vectors.json", "VEYQ_NORMALIZATION_TEST": "/veyq/NormalizationTest.txt",
           "TZ": ZONE}
    # WallClockTest's values are Berlin's. kotlinx-datetime reads the zone from /etc/localtime, glibc from TZ: both set.
    prelude = f"ln -sf /usr/share/zoneinfo/{ZONE} /etc/localtime; "
    filter_arg = " " + shlex.quote(f"--ktest_filter={pattern}") if pattern else ""
    return in_container(docker_platform, mounts, env, f"{prelude}exec /veyq/bin/test.kexe{filter_arg}")


def in_container(docker_platform: str, mounts: dict[Path, str], env: dict[str, str], script: str,
                 writable: dict[Path, str] | None = None, volumes: dict[str, str] | None = None,
                 docker_args: list[str] | None = None, secrets: dict[str, str] | None = None) -> int:
    """Runs bash [script] in the image, [mounts] read-only, [writable] read-write, [volumes] the named volumes; with
    VEYQ_LIVE, the dev origin reachable under its own name and the seeded principal's env file at /veyq/env. [secrets]
    reach the container by name only (`-e NAME`, the value in docker's own environment), so the printed command holds
    none of them. Returns the script's exit code."""
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
        origin = urlsplit(live)
        if origin.hostname and (origin.hostname == "localhost" or origin.hostname.endswith(".localhost")):
            port = origin.port or (443 if origin.scheme == "https" else 80)
            docker += ["--add-host", f"{origin.hostname}:127.0.0.1"]
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
          env: dict[str, str] | None = None, secrets: dict[str, str] | None = None) -> int:
    """Runs [script] in the package's directory in the container, with what build.rs and the tests read."""
    mounts = {repo_layout.MOBILE_LINUX: "/veyq/src", GENERATED: "/veyq/generated", lib_dir: "/veyq/lib",
              WORDS / "words.json": "/veyq/words.json"}
    base = {"CARGO_HOME": "/veyq/cargo", "CARGO_TARGET_DIR": "/veyq/target", "VEYQ_GENERATED": "/veyq/generated",
            "VEYQ_LIB_DIR": "/veyq/lib", "LD_LIBRARY_PATH": "/veyq/lib", "VEYQ_SAMPLES": "/veyq/generated/bridge/samples",
            "VEYQ_WORDS_JSON": "/veyq/words.json", "LANG": "en_US.UTF-8"}
    return in_container(docker_platform, mounts, base | (env or {}), f"set -e; cd /veyq/src; {script}",
                        volumes=CARGO_VOLUMES, docker_args=docker_args, secrets=secrets)


def build() -> int:
    docker_platform, target = arch()
    lib_dir = generate(target)
    build_image(docker_platform)
    return cargo(docker_platform, lib_dir, "cargo clippy --locked --all-targets -- -D warnings; "
                                           "cargo test --locked; cargo build --locked")


def dev_principal() -> dict[str, str]:
    """The seeded dev principal as the debug build's VEYQ_DEV_* (DevLogin.swift's names): the server is VEYQ_LIVE's
    origin, the rest the env file's (VEYQ_ENV_FILE, else `.run/.env`). Nothing of it is printed."""
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
    return {"VEYQ_DEV_SERVER": live, "VEYQ_DEV_EMAIL": values["VEYQ_EMAIL"], "VEYQ_DEV_PASSWORD": values["VEYQ_PASSWORD"],
            "VEYQ_DEV_SECRET_KEY": values["VEYQ_SECRET_KEY"]}


def run_app(port: int, devlogin: bool) -> int:
    secrets = dev_principal() if devlogin else None
    docker_platform, target = arch()
    lib_dir = generate(target)
    build_image(docker_platform)
    inner = 8080 + BROADWAY_DISPLAY
    env = {k: os.environ[k] for k in ("LANGUAGE", "ADW_DEBUG_COLOR_SCHEME") if k in os.environ}
    # the app's locale (its money, its dates): VEYQ_LOCALE, never the host's own LANG, which says nothing of the image's
    if "VEYQ_LOCALE" in os.environ:
        env["LANG"] = os.environ["VEYQ_LOCALE"]
    env |= {"GDK_BACKEND": "broadway", "BROADWAY_DISPLAY": f":{BROADWAY_DISPLAY}", "XDG_RUNTIME_DIR": "/tmp/xdg"}
    print(f"veyq: the app draws into http://127.0.0.1:{port} once cargo built it", flush=True)
    return cargo(docker_platform, lib_dir,
                 "cargo build --locked; mkdir -p -m 700 /tmp/xdg; "
                 f"gtk4-broadwayd --address 0.0.0.0 :{BROADWAY_DISPLAY} & "
                 # ready once its socket is there (a bare TCP probe makes it log "Error reading request lines")
                 "for _ in $(seq 50); do ls /tmp/xdg/broadway*.socket >/dev/null 2>&1 && break; sleep 0.1; done; "
                 "exec /veyq/target/debug/veyq",
                 docker_args=["-p", f"127.0.0.1:{port}:{inner}"], env=env, secrets=secrets)


def lock() -> int:
    docker_platform, _ = arch()
    build_image(docker_platform)
    return in_container(docker_platform, {}, {"CARGO_HOME": "/veyq/cargo"}, "set -e; cd /veyq/src; cargo update",
                        writable={repo_layout.MOBILE_LINUX: "/veyq/src"}, volumes={"veyq-linux-cargo": "/veyq/cargo"})


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
    verbs.add_parser("bridge", help="link the bridge's library, check what it exports and run the C smoke against it")
    verbs.add_parser("build", help="generate, then cargo clippy, test and build the app in the container")
    r = verbs.add_parser("run", help="build the app and run it under gtk4-broadwayd, drawn into a browser")
    r.add_argument("--port", type=int, default=8095, help="the port on 127.0.0.1 the browser opens (default 8095)")
    r.add_argument("--devlogin", action="store_true", help="sign the debug build in as the seeded principal (needs VEYQ_LIVE)")
    verbs.add_parser("lock", help="cargo update: write Cargo.lock anew")
    args = parser.parse_args()
    if args.verb == "build":
        sys.exit(build())
    if args.verb == "run":
        sys.exit(run_app(args.port, args.devlogin))
    if args.verb == "lock":
        sys.exit(lock())
    if args.verb == "test":
        sys.exit(test(args.tests))
    if args.verb == "bridge":
        sys.exit(bridge())


if __name__ == "__main__":
    main()
