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
  bridge                     links the bridge's release DLL (`:bridge:linkReleaseSharedMingwX64`), checks its export
                             table on this host (`objdump -p`) — exactly bridge.h's six functions and Kotlin/Native's
                             `veyq_symbols` —, cross-compiles `bridge/smoke/smoke.c` for mingw against bridge.h alone
                             (Kotlin/Native's clang, an import library written from the DLL's .def) and runs it on the
                             Windows host beside the DLL; with VEYQ_LIVE the smoke also signs in as the seeded
                             principal over the DLL's own HTTP client (WinHTTP).
  build                      generates the bridge's C# (`:bridge:generateBindings`, Bridge.g.cs) and the samples its
                             round trip reads (`WireTest`), links the debug DLL, and holds the C# packages to the
                             deprecation gate (`dotnet package list --deprecated` and `--vulnerable`, every package,
                             transitive ones too: red on any). Then `dotnet test` of Veyq.Bridge.Tests here, and on the
                             Windows host, as an x64 process beside the DLL (`--arch x64`; an ARM64 host runs it under
                             emulation with the x64 .NET runtime). Every restore is `--locked-mode`.

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

The tree goes to the Windows host as a tar (its in-box `tar.exe`): exactly this host's working tree of what a verb
needs, under the same relative paths below `VEYQ_WINDOWS_DIR`, never a checkout of its own. .NET's telemetry is off for
every `dotnet` this script runs, here and there.
"""
from __future__ import annotations

import argparse
import base64
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
from pathlib import Path, PureWindowsPath
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
import repo_layout  # noqa: E402

sys.path.insert(0, str(repo_layout.MOBILE / "core"))
import konan_toolchain  # noqa: E402

VECTORS = ROOT / "contracts" / "crypto" / "vectors" / "v1.json"
NORMALIZATION_TEST = repo_layout.MOBILE / "build" / "utf8proc" / "NormalizationTest.txt"
TEST_BINARY = repo_layout.MOBILE / "core" / "build" / "bin" / "mingwX64" / "debugTest" / "test.exe"
BRIDGE_H = repo_layout.MOBILE / "bridge" / "bridge.h"
SMOKE_C = repo_layout.MOBILE / "bridge" / "smoke" / "smoke.c"
DLL_DIR = {kind: repo_layout.MOBILE / "bridge" / "build" / "bin" / "mingwX64" / f"{kind}Shared" for kind in ("debug", "release")}
GENERATED = repo_layout.MOBILE / "build" / "generated"
SMOKE_OUT = repo_layout.MOBILE / "build" / "windows" / "smoke"
HERE = repo_layout.MOBILE_WINDOWS
BRIDGE_TESTS = "Veyq.Bridge.Tests"
# What Kotlin/Native exports from a DLL beside the @CName functions: its C API's entry (`<library>_symbols`), read from
# the export table of `veyq.dll` (2026-11-10, Kotlin 2.4.21). A new one is read and added here, never waved by.
KOTLIN_NATIVE_EXPORTS = {"veyq_symbols"}
# .NET's telemetry off; its words English (the VM's are German, and dotnet writes UTF-8 where PowerShell reads CP850)
DOTNET_ENV = {"DOTNET_CLI_TELEMETRY_OPTOUT": "1", "TESTINGPLATFORM_TELEMETRY_OPTOUT": "1", "DOTNET_NOLOGO": "1",
              "DOTNET_CLI_UI_LANGUAGE": "en-US"}

SSH_TARGET = os.environ.get("VEYQ_WINDOWS_SSH", "kevin@192.168.64.8")
SSH_KEY = Path(os.environ.get("VEYQ_WINDOWS_KEY", Path.home() / ".ssh" / "veyq_vms_ed25519")).expanduser()
REMOTE_DIR = PureWindowsPath(os.environ.get("VEYQ_WINDOWS_DIR", r"F:\veyq"))
CODE_PAGE = "cp850"  # the console code page PowerShell writes in over ssh on the (German) test VM


def run(*args: str, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True, env=os.environ | (env or {}))


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


def exports(dll: Path) -> list[str]:
    """The names in [dll]'s export table, read with this host's `objdump -p` (LLVM's: Apple's, or `llvm-objdump`)."""
    tool = shutil.which("llvm-objdump") or shutil.which("objdump") or sys.exit("no objdump on this host")
    text = subprocess.run([tool, "-p", str(dll)], check=True, capture_output=True, text=True).stdout
    table = text.split("Export Table:", 1)
    if len(table) < 2:
        return []
    names = []
    for line in table[1].splitlines()[1:]:
        if not line.strip():
            break
        m = re.match(r"^\s+\d+\s+0x[0-9a-fA-F]+\s+(\S+)$", line)
        if m:
            names.append(m.group(1))
    return names


def export_gate(dll: Path) -> bool:
    """[dll] exports exactly bridge.h's six functions and Kotlin/Native's own symbols: nothing of the static libraries
    linked in (bridge/build.gradle.kts hands the linker Kotlin/Native's .def)."""
    header = re.sub(r"/\*.*?\*/", "", BRIDGE_H.read_text(), flags=re.S)
    expected = sorted(set(re.findall(r"\b(veyq_[a-z]+)\s*\(", header)) | KOTLIN_NATIVE_EXPORTS)
    found = sorted(exports(dll))
    print(f"+ objdump -p {dll.name}: {len(found)} exports", flush=True)
    if found != expected:
        print(f"veyq: {dll.name} exports {sorted(set(found) - set(expected))[:40]} beyond bridge.h's and Kotlin/Native's,"
              f" and lacks {sorted(set(expected) - set(found))}", flush=True)
        return False
    print("the DLL exports bridge.h's six functions and Kotlin/Native's own symbol, nothing else", flush=True)
    return True


def smoke_exe(dll_dir: Path) -> Path:
    """`bridge/smoke/smoke.c` cross-compiled for mingw x64 by Kotlin/Native's clang against bridge.h alone, linked
    against an import library written from the DLL's .def (its winpthreads static: the exe needs only the DLL)."""
    tc = konan_toolchain.for_slice("mingw_x64")
    SMOKE_OUT.mkdir(parents=True, exist_ok=True)
    definition = SMOKE_OUT / "veyq.def"
    definition.write_text("LIBRARY veyq.dll\n" + (dll_dir / "veyq.def").read_text())
    implib = SMOKE_OUT / "veyq.lib"
    run(str(tc.clang.parent / "lld"), "-flavor", "link", "/lib", f"/def:{definition}", "/machine:x64", f"/out:{implib}")
    exe = SMOKE_OUT / "smoke.exe"
    run(str(tc.clang), *tc.ldflags(), "-std=c11", "-Wall", "-Wextra", "-Werror", "-D_DEFAULT_SOURCE",
        f"-I{BRIDGE_H.parent}", str(SMOKE_C), str(implib), "-static", "-lpthread", "-o", str(exe))
    return exe


def bridge() -> int:
    run("./gradlew", ":bridge:linkReleaseSharedMingwX64", cwd=repo_layout.MOBILE)
    dll = DLL_DIR["release"] / "veyq.dll"
    if not dll.exists():
        sys.exit(f"no DLL at {dll}")
    if not export_gate(dll):
        return 1
    exe = smoke_exe(dll.parent)
    remote = REMOTE_DIR / "bridge"
    copy_to([dll, exe], remote)
    env: dict[str, str] = {}
    forward: list[str] | None = None
    secret = remote / "principal.env"
    if os.environ.get("VEYQ_LIVE"):
        forward, live = live_forward()
        copy_to([env_file()], remote, as_name=secret.name)
        env |= {"VEYQ_LIVE": live, "VEYQ_ENV_FILE": str(secret)}
    script = "\n".join([
        *(f"$env:{k} = {ps_quote(v)}" for k, v in env.items()),
        f"Set-Location {ps_quote(str(remote))}",
        f"& {ps_quote(str(remote / exe.name))}",
        "$code = $LASTEXITCODE",
        f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(secret))}",
        "exit $code",
    ])
    try:
        return powershell(script, forward)
    finally:
        if os.environ.get("VEYQ_LIVE"):
            powershell(f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(secret))}", quiet=True)


def dotnet(*args: str) -> None:
    run("dotnet", *args, cwd=HERE, env=DOTNET_ENV)


def deprecation_gate(project: str) -> bool:
    """Red on any package of [project] — transitive ones too — that nuget.org marks deprecated or vulnerable. It reads
    the graph the locked restore before it wrote (`--no-restore`: the verb would restore on its own otherwise)."""
    flagged = []
    for kind in ("--deprecated", "--vulnerable"):
        args = ["dotnet", "package", "list", "--project", project, kind, "--include-transitive", "--no-restore",
                "--format", "json"]
        print("+ " + " ".join(args), flush=True)
        done = subprocess.run(args, cwd=HERE, env=os.environ | DOTNET_ENV, capture_output=True, text=True)
        try:
            report = json.loads(done.stdout)
        except json.JSONDecodeError:
            sys.exit(f"dotnet package list wrote no report (exit {done.returncode}): {done.stdout}{done.stderr}")
        for problem in report.get("problems", []):
            sys.exit(f"dotnet package list: {problem.get('text', problem)}")
        if done.returncode != 0:
            sys.exit(f"dotnet package list exited {done.returncode}: {done.stderr}")
        for p in report.get("projects", []):
            for fw in p.get("frameworks", []):
                for pkg in fw.get("topLevelPackages", []) + fw.get("transitivePackages", []):
                    flagged.append(f"{pkg['id']} {pkg.get('resolvedVersion', '')} ({kind[2:]})")
    if flagged:
        print("veyq: packages nuget.org marks so: " + ", ".join(sorted(set(flagged))), flush=True)
        return False
    print("no package is deprecated or vulnerable", flush=True)
    return True


def tree_tar(entries: dict[Path, str], exclude: set[str]) -> bytes:
    """A tar of [entries] (a file or a directory here -> its path in the archive), without anything inside a directory
    entry whose path there has a part in [exclude] (a C# project's own `bin` and `obj`)."""
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w") as tar:
        for source, name in entries.items():
            def keep(info: tarfile.TarInfo, top: str = name) -> tarfile.TarInfo | None:
                inside = Path(info.name).relative_to(top).parts if info.name != top else ()
                return None if exclude & set(inside) else info
            tar.add(source, arcname=name, filter=keep)
    return buf.getvalue()


def build() -> int:
    run("./gradlew", ":bridge:generateBindings", ":bridge:linkDebugSharedMingwX64", ":bridge:jvmTest", "--tests",
        "*WireTest*", cwd=repo_layout.MOBILE)
    dll = DLL_DIR["debug"] / "veyq.dll"
    if not dll.exists():
        sys.exit(f"no DLL at {dll}")
    dotnet("restore", "--locked-mode", BRIDGE_TESTS)
    if not deprecation_gate(BRIDGE_TESTS):
        return 1
    dotnet("test", "--project", BRIDGE_TESTS, "--no-restore")

    # The Windows host: the C# tree, the generated bridge and the samples under their paths here, the DLL beside them.
    mobile = PureWindowsPath(REMOTE_DIR) / "src" / "mobile"
    archive = SMOKE_OUT.parent / "tree.tar"
    archive.parent.mkdir(parents=True, exist_ok=True)
    archive.write_bytes(tree_tar({HERE: "src/mobile/windows", GENERATED / "bridge": "src/mobile/build/generated/bridge",
                                  dll: "bin/veyq.dll"}, exclude={"bin", "obj"}))
    copy_to([archive], REMOTE_DIR)
    tests = mobile / "windows" / BRIDGE_TESTS
    script = "\n".join([
        *(f"$env:{k} = {ps_quote(v)}" for k, v in DOTNET_ENV.items()),
        f"$env:VEYQ_SAMPLES = {ps_quote(str(mobile / 'build' / 'generated' / 'bridge' / 'samples'))}",
        f"Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(REMOTE_DIR / 'src'))}, {ps_quote(str(REMOTE_DIR / 'bin'))}",
        f"Set-Location {ps_quote(str(REMOTE_DIR))}",
        "tar.exe -xf tree.tar; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }",
        "Remove-Item -Force tree.tar",
        f"Set-Location {ps_quote(str(mobile / 'windows'))}",
        "Write-Output ('+ dotnet ' + (dotnet --version))",
        f"dotnet restore --locked-mode {BRIDGE_TESTS}; if ($LASTEXITCODE -ne 0) {{ exit $LASTEXITCODE }}",
        f"dotnet test --project {BRIDGE_TESTS} --no-restore --arch x64 -p:VeyqNative={ps_quote(str(REMOTE_DIR / 'bin' / 'veyq.dll'))}",
        "exit $LASTEXITCODE",
    ])
    return powershell(script)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    verbs = parser.add_subparsers(dest="verb", required=True)
    t = verbs.add_parser("test", help="link the core's Windows test binary and run it on the Windows host")
    t.add_argument("--tests", help="Kotlin/Native --ktest_filter pattern, e.g. '*LiveSignInTest*'")
    verbs.add_parser("bridge", help="link the bridge's DLL, check its export table and run the C smoke on the Windows host")
    verbs.add_parser("build", help="generate the bridge's C#, gate the packages, dotnet test here and on the Windows host")
    args = parser.parse_args()
    if args.verb == "test":
        sys.exit(test(args.tests))
    if args.verb == "bridge":
        sys.exit(bridge())
    if args.verb == "build":
        sys.exit(build())


if __name__ == "__main__":
    main()
