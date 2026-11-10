#!/usr/bin/env python3
"""The one entry point for everything Linux of the veyq app (CR core-as-a-native-library D8): it builds the Fedora 44
image of `Containerfile` beside it and runs what a verb names in it. Everything Kotlin is cross-compiled on this host
by Gradle; only running it needs Linux.

Verbs:
  test [--tests <pattern>]   links the core's Linux test binary for this host's architecture
                             (`:core:linkDebugTest<Target>`) and runs it in the container: the conformance suite over
                             libsodium, utf8proc's NormalizationTest, the wall clock in Europe/Berlin, and — with
                             VEYQ_LIVE set — the live sign-in. `--tests` is Kotlin/Native's `--ktest_filter` pattern
                             (`*LiveSignInTest*`). Its exit code is the test binary's.

Live runs: `VEYQ_LIVE=http://veyq.localhost:8080 run.py test --tests '*LiveSignInTest*'` signs in as the seeded
principal of `.run/.env` (VEYQ_ENV_FILE names another file, e.g. the main checkout's from a worktree). The backend picks
the tenant from the Host header, so the container keeps the origin's name: a `*.localhost` origin resolves to the
container's own loopback there, where socat forwards its port to the host (`host.docker.internal`), and every tenant of
the account (each a `*.localhost` name on that port) arrives under its own name.

Docker must answer `docker build` / `docker run`; the image is `veyq-linux-dev`, rebuilt from its layer cache each run.
"""
from __future__ import annotations

import argparse
import os
import platform
import shlex
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
import repo_layout  # noqa: E402

HERE = Path(__file__).resolve().parent
IMAGE = "veyq-linux-dev"
ZONE = "Europe/Berlin"
VECTORS = ROOT / "contracts" / "crypto" / "vectors" / "v1.json"
NORMALIZATION_TEST = repo_layout.MOBILE / "build" / "utf8proc" / "NormalizationTest.txt"
# This host's architecture -> (Docker platform, Kotlin/Native target as Gradle names it)
ARCHES = {"arm64": ("linux/arm64", "LinuxArm64"), "aarch64": ("linux/arm64", "LinuxArm64"),
          "x86_64": ("linux/amd64", "LinuxX64"), "AMD64": ("linux/amd64", "LinuxX64")}


def run(*args: str, cwd: Path | None = None) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)


def build_image(docker_platform: str) -> None:
    run("docker", "build", "--platform", docker_platform, "-t", IMAGE, "-f", str(HERE / "Containerfile"), str(HERE))


def test(pattern: str | None) -> int:
    try:
        docker_platform, target = ARCHES[platform.machine()]
    except KeyError:
        sys.exit(f"no Linux target for this host's architecture ({platform.machine()})")
    run("./gradlew", f":core:linkDebugTest{target}", cwd=repo_layout.MOBILE)
    kexe_dir = repo_layout.MOBILE / "core" / "build" / "bin" / (target[0].lower() + target[1:]) / "debugTest"
    if not (kexe_dir / "test.kexe").exists():
        sys.exit(f"no test binary at {kexe_dir / 'test.kexe'}")
    build_image(docker_platform)

    mounts = {kexe_dir: "/veyq/bin", VECTORS: "/veyq/vectors.json", NORMALIZATION_TEST: "/veyq/NormalizationTest.txt"}
    env = {"VEYQ_VECTORS": "/veyq/vectors.json", "VEYQ_NORMALIZATION_TEST": "/veyq/NormalizationTest.txt",
           "TZ": ZONE}
    docker = ["docker", "run", "--rm", "--platform", docker_platform]
    # WallClockTest's values are Berlin's. kotlinx-datetime reads the zone from /etc/localtime, glibc from TZ: both set.
    prelude = f"ln -sf /usr/share/zoneinfo/{ZONE} /etc/localtime; "
    live = os.environ.get("VEYQ_LIVE")
    if live:
        env_file = Path(os.environ.get("VEYQ_ENV_FILE", ROOT / ".run" / ".env"))
        if not env_file.exists():
            sys.exit(f"VEYQ_LIVE needs the seeded principal's {env_file} (VEYQ_ENV_FILE names another)")
        mounts[env_file] = "/veyq/env"
        env |= {"VEYQ_LIVE": live, "VEYQ_ENV_FILE": "/veyq/env"}
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
    filter_arg = " " + shlex.quote(f"--ktest_filter={pattern}") if pattern else ""
    docker += [IMAGE, "bash", "-c", f"{prelude}exec /veyq/bin/test.kexe{filter_arg}"]
    print("+ " + " ".join(docker), flush=True)
    return subprocess.run(docker).returncode


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    verbs = parser.add_subparsers(dest="verb", required=True)
    t = verbs.add_parser("test", help="link the core's Linux test binary and run it in the container")
    t.add_argument("--tests", help="Kotlin/Native --ktest_filter pattern, e.g. '*LiveSignInTest*'")
    args = parser.parse_args()
    if args.verb == "test":
        sys.exit(test(args.tests))


if __name__ == "__main__":
    main()
