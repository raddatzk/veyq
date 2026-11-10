#!/usr/bin/env python3
"""The one entry point for everything Windows of the veyq app (ADR 0127 decision 5), run on this host (a Mac or Linux):
everything Kotlin is cross-compiled here by Gradle (Kotlin/Native's `mingwX64`); what needs Windows runs on a Windows
host reached over ssh. No Windows host builds Kotlin.

Verbs:
  test [--tests <pattern>]   links the core's Windows test binary (`:core:linkDebugTestMingwX64`), copies it with the
                             crypto vectors and Unicode's NormalizationTest.txt to the Windows host and runs it there:
                             the whole common suite, the conformance over libsodium, `NormalizationTest` and
                             `WallClockTest` (in the host's own zone, which must be Europe/Berlin's, W. Europe Standard
                             Time). `--tests` is Kotlin/Native's `--ktest_filter` pattern (`*LiveSignInTest*`). Its exit
                             code is the test binary's.

The Windows host: `VEYQ_WINDOWS_SSH` (default `kevin@192.168.64.8`, the test VM of docs/crosscutting/native-apps.md),
its key `VEYQ_WINDOWS_KEY` (default `~/.ssh/veyq_vms_ed25519`), and a directory there `VEYQ_WINDOWS_DIR` (default
`F:\\veyq`, the VM's Dev Drive) which this script owns. The host is x64 or ARM64: the binary is x64 and runs under
Windows' x64 emulation on ARM64. ssh lands in session 0 there, which is enough for a test binary. Every PowerShell
script goes as `-EncodedCommand` (UTF-16LE, base64: no quoting through cmd.exe), and what it prints is read as the
console's code page, CP850 on the German VM.

Live runs: `VEYQ_LIVE=http://veyq.localhost:8080 run.py test --tests '*LiveSignInTest*'` signs in as the seeded
principal of `.run/.env` (VEYQ_ENV_FILE names another file, e.g. the main checkout's from a worktree) over WinHTTP. The
ssh session that runs the test forwards the origin's port on the Windows host's loopback to this host's
(`ssh -R`), and the backend picks the tenant from the Host header, so the Windows host must resolve the origin's name
(and every tenant's) to 127.0.0.1 in its `hosts` file: Windows resolves no `*.localhost` by itself. The env file goes
to the host as a file the test reads, never on a command line, and is deleted after the run.
"""
from __future__ import annotations

import argparse
import base64
import os
import subprocess
import sys
from pathlib import Path, PureWindowsPath
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
import repo_layout  # noqa: E402

VECTORS = ROOT / "contracts" / "crypto" / "vectors" / "v1.json"
NORMALIZATION_TEST = repo_layout.MOBILE / "build" / "utf8proc" / "NormalizationTest.txt"
TEST_BINARY = repo_layout.MOBILE / "core" / "build" / "bin" / "mingwX64" / "debugTest" / "test.exe"

SSH_TARGET = os.environ.get("VEYQ_WINDOWS_SSH", "kevin@192.168.64.8")
SSH_KEY = Path(os.environ.get("VEYQ_WINDOWS_KEY", Path.home() / ".ssh" / "veyq_vms_ed25519")).expanduser()
REMOTE_DIR = PureWindowsPath(os.environ.get("VEYQ_WINDOWS_DIR", r"F:\veyq"))
CODE_PAGE = "cp850"  # the console code page PowerShell writes in over ssh on the (German) test VM


def run(*args: str, cwd: Path | None = None) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)


def ssh_base(forward: list[str] | None = None) -> list[str]:
    return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-o", "ExitOnForwardFailure=yes",
            "-i", str(SSH_KEY), *(forward or []), SSH_TARGET]


def powershell(script: str, forward: list[str] | None = None, quiet: bool = False) -> int:
    """Runs [script] in PowerShell on the Windows host and streams what it prints, decoded from the console's code page.
    The first connection after the host idled can time out once (ssh's 255 before anything ran): it is tried again."""
    encoded = base64.b64encode(("$ProgressPreference = 'SilentlyContinue'\n" + script).encode("utf-16-le")).decode()
    command = ssh_base(forward) + ["powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded]
    for attempt in (1, 2):
        if not quiet:
            print(f"+ ssh {SSH_TARGET} powershell <{len(script.splitlines())} lines>", flush=True)
        printed = False
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as p:
            assert p.stdout is not None
            for line in p.stdout:
                printed = True
                sys.stdout.write(line.decode(CODE_PAGE, errors="replace").replace("\r\n", "\n"))
                sys.stdout.flush()
        if p.returncode == 255 and not printed and attempt == 1:
            print("veyq: ssh did not connect; trying once more", flush=True)
            continue
        return p.returncode
    return 255


def copy_to(files: list[Path], remote_dir: PureWindowsPath, as_name: str | None = None) -> None:
    """Copies [files] into [remote_dir] on the Windows host (created if missing); one file may take [as_name] there."""
    if powershell(f"New-Item -ItemType Directory -Force -Path {ps_quote(str(remote_dir))} | Out-Null", quiet=True) != 0:
        sys.exit(f"cannot create {remote_dir} on {SSH_TARGET}")
    target = str(remote_dir).replace("\\", "/") + "/" + (as_name or "")
    run("scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-i", str(SSH_KEY), *map(str, files),
        f"{SSH_TARGET}:{target}")


def ps_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def live_forward() -> tuple[list[str], str]:
    """With VEYQ_LIVE: the `ssh -R` that makes the origin's port on the Windows host's loopback this host's, and the
    origin. The name stays the origin's, which the Windows host's `hosts` maps to 127.0.0.1."""
    live = os.environ["VEYQ_LIVE"]
    origin = urlsplit(live)
    if not origin.hostname:
        sys.exit(f"VEYQ_LIVE is not an origin: {live}")
    port = origin.port or (443 if origin.scheme == "https" else 80)
    return ["-R", f"{port}:127.0.0.1:{port}"], live


def env_file() -> Path:
    path = Path(os.environ.get("VEYQ_ENV_FILE", ROOT / ".run" / ".env"))
    if not path.exists():
        sys.exit(f"VEYQ_LIVE needs the seeded principal's {path} (VEYQ_ENV_FILE names another)")
    return path


def test(pattern: str | None) -> int:
    run("./gradlew", ":core:linkDebugTestMingwX64", cwd=repo_layout.MOBILE)
    if not TEST_BINARY.exists():
        sys.exit(f"no test binary at {TEST_BINARY}")
    remote = REMOTE_DIR / "test"
    copy_to([TEST_BINARY, VECTORS, NORMALIZATION_TEST], remote)
    env = {"VEYQ_VECTORS": str(remote / VECTORS.name), "VEYQ_NORMALIZATION_TEST": str(remote / NORMALIZATION_TEST.name)}
    forward: list[str] | None = None
    secret = remote / "principal.env"
    if os.environ.get("VEYQ_LIVE"):
        forward, live = live_forward()
        copy_to([env_file()], remote, as_name=secret.name)
        env |= {"VEYQ_LIVE": live, "VEYQ_ENV_FILE": str(secret)}
    args = f" {ps_quote('--ktest_filter=' + pattern)}" if pattern else ""
    script = "\n".join([
        *(f"$env:{k} = {ps_quote(v)}" for k, v in env.items()),
        # the zone WallClockTest reads, said before its verdict
        "Write-Output (\"windows zone: \" + (Get-TimeZone).Id)",
        f"& {ps_quote(str(remote / TEST_BINARY.name))}{args}",
        "$code = $LASTEXITCODE",
        f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(secret))}",
        "exit $code",
    ])
    try:
        return powershell(script, forward)
    finally:
        if os.environ.get("VEYQ_LIVE"):
            # the test's own script removed it; this is for a run that ended before it got there
            powershell(f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(secret))}", quiet=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    verbs = parser.add_subparsers(dest="verb", required=True)
    t = verbs.add_parser("test", help="link the core's Windows test binary and run it on the Windows host")
    t.add_argument("--tests", help="Kotlin/Native --ktest_filter pattern, e.g. '*LiveSignInTest*'")
    args = parser.parse_args()
    if args.verb == "test":
        sys.exit(test(args.tests))


if __name__ == "__main__":
    main()
