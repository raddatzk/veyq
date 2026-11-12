#!/usr/bin/env python3
"""The one entry point for everything Windows of the veyq app (ADR 0129 decision 5), run on this host (a Mac or Linux):
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
  build                      generates what the app is built from but does not keep — the bridge's C#
                             (`:bridge:generateBindings`, Bridge.g.cs) and the samples its round trip reads (`WireTest`),
                             the words (`generate_words.py --resw`: the .resw catalogs and Words.g.cs) and the theme
                             (`generate_theme.py --winui`: Theme.xaml) — and links the debug DLL. Here: every C# project
                             restored `--locked-mode` and held to the deprecation gate (`dotnet package list --deprecated`
                             and `--vulnerable`, transitive packages too: red on any), then `dotnet test` of
                             Veyq.Bridge.Tests. On the Windows host: the same locked restores, the app built (Veyq, WinUI,
                             unpackaged, self-contained, x64) and Veyq.Drive, then `dotnet test` of Veyq.Bridge.Tests beside
                             the DLL and of Veyq.Tests against the app's .pri — the words in English and German, money and
                             days in two cultures, sizes in the host's — each an x64 process (`--arch x64`; an ARM64 host
                             runs it under emulation with the x64 .NET runtime).
  run [--width W --height H] [--devlogin [--fill|--unlock]]
                             builds the app as `build` does (without its tests), stops a running one, puts it in
                             `<dir>\app` and starts it in the logged-on session through the scheduled task `veyq-app`
                             (Veyq.Drive's launch); W × H resizes its window. Then it holds the tunnel to the backend
                             (`ssh -R`, VEYQ_LIVE's port, default http://veyq.localhost:8080) until it is ended (Ctrl+C);
                             the app keeps running. A debug build reads VEYQ_THEME (light, dark) and VEYQ_LANGUAGE (en-US,
                             de-DE) from this host's environment. `--devlogin` hands the debug build the seeded
                             principal of the env file (VEYQ_ENV_FILE, else `.run/.env`) as VEYQ_DEV_*, inside the
                             launch file Veyq.Drive deletes on reading — never on a command line —, and it signs in
                             through its own sign-in once (DevLogin.cs). `--fill` only fills the sign-in, for the pass to
                             press Sign in; `--unlock` hands the password alone, which the unlock of a password-trusted
                             account takes, and Manage's way back to the password fills its field with it once.
  drive <step>...            runs Veyq.Drive's steps (its Program.cs: tree, invoke, select, set, type, key, press,
                             size, maximize, wait, sleep, shot) against the running app through the scheduled task
                             `veyq-drive`, and prints the app's log first, then what each step saw. `shot <name>.png`
                             lands in src/mobile/build/windows/shots here.
  hello-probe [--timeout S]  the Hello half of the core's WindowsDeviceKeys, which needs the person at the Windows host's
                             console (a PIN is never typed by an agent): links the core's test binary, rebuilds
                             Veyq.Drive there (as `build` does), and starts the binary's `HelloProbe` in the logged-on
                             session through the scheduled task `veyq-app`. It seals under a Passport key of its own, then
                             asks for the PIN (enter it), opens within the 15 s window without asking, asks once more
                             after it (press Cancel), and deletes the key. Each step's line is printed as the probe
                             writes it (`<step> asking: …` while a prompt waits); it waits until the probe ends (`done`,
                             or its process gone), S seconds at most (3600 by default: a step waits for a person), and
                             fetches the outcome last, to build/windows/hello-probe.txt here. `--check` stops
                             the probe before its first key: the launch and Hello's availability in that session, no
                             prompt.
  register                   registers the two scheduled tasks, interactive only, as the user ssh logs in as: they run
                             on demand in the logged-on session, which an ssh command (session 0) cannot reach. Run once
                             per Windows host, by its owner (a persistent change on that machine).
The Windows host: `VEYQ_WINDOWS_SSH` (default `kevin@192.168.64.8`, the test VM of docs/crosscutting/native-apps.md),
its key `VEYQ_WINDOWS_KEY` (default `~/.ssh/veyq_vms_ed25519`), and a directory there `VEYQ_WINDOWS_DIR` (default
`F:\\veyq`, the VM's Dev Drive) which this script owns. The host is x64 or ARM64: the binary is x64 and runs under
Windows' x64 emulation on ARM64. ssh lands in session 0 there, which is enough for a test binary but shows no window:
the app and its driver start in the logged-on session through the scheduled tasks `register` sets up. Every PowerShell
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
import time
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
WORDS = repo_layout.MOBILE / "words"
THEME = repo_layout.MOBILE / "theme"
BRIDGE_TESTS = "Veyq.Bridge.Tests"
APP = "Veyq"
APP_TESTS = "Veyq.Tests"
DRIVE = "Veyq.Drive"
PROJECTS = (BRIDGE_TESTS, APP, APP_TESTS, DRIVE)  # every one restored locked and held to the deprecation gate
SHOTS = repo_layout.MOBILE / "build" / "windows" / "shots"
# The app's build output on the Windows host, below src/mobile/windows (the app is x64, unpackaged, self-contained).
APP_OUT = PureWindowsPath(APP, "bin", "Debug", "net10.0-windows10.0.26100.0", "win-x64")
DRIVE_OUT = PureWindowsPath(DRIVE, "bin", "Release", "net10.0-windows")
TASKS = {"veyq-app": "--launch", "veyq-drive": "--drive"}  # each scheduled task and Veyq.Drive's mode it runs
# What Kotlin/Native exports from a DLL beside the @CName functions: its C API's entry (`<library>_symbols`), read from
# the export table of `veyq.dll` (2026-11-10, Kotlin 2.4.21). A new one is read and added here, never waved by.
KOTLIN_NATIVE_EXPORTS = {"veyq_symbols"}
# .NET's telemetry off; its words English (the VM's are German, and dotnet writes UTF-8 where PowerShell reads CP850)
DOTNET_ENV = {"DOTNET_CLI_TELEMETRY_OPTOUT": "1", "TESTINGPLATFORM_TELEMETRY_OPTOUT": "1", "DOTNET_NOLOGO": "1",
              "DOTNET_CLI_UI_LANGUAGE": "en-US"}

SSH_TARGET = os.environ.get("VEYQ_WINDOWS_SSH", "kevin@192.168.64.8")
SSH_KEY = Path(os.environ.get("VEYQ_WINDOWS_KEY", Path.home() / ".ssh" / "veyq_vms_ed25519")).expanduser()
REMOTE_DIR = PureWindowsPath(os.environ.get("VEYQ_WINDOWS_DIR", r"F:\veyq"))
REMOTE_MOBILE = REMOTE_DIR / "src" / "mobile"
APP_NAME = "Veyq.App"  # the app's assembly, exe and .pri name (Veyq.csproj): never veyq, the core's DLL
REMOTE_APP = REMOTE_DIR / "app"  # the app run.py starts, copied out of the build so a running one blocks no build
REMOTE_DRIVE = REMOTE_DIR / "drive"  # Veyq.Drive, which the scheduled tasks run
REMOTE_RUN = REMOTE_DIR / "run"  # the launch's and the drive's files
REMOTE_LOG = "AppData/Local/veyq/log.txt"  # the app's log (Log.cs, %LOCALAPPDATA%), from the ssh user's home
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


def live_forward(live: str | None = None) -> tuple[list[str], str]:
    """The `ssh -R` that makes the origin's port ([live], else VEYQ_LIVE's) — and VEYQ_LIVE_STORE's, the object store's
    public endpoint — on the Windows host's loopback this host's, and the origin. The name stays the origin's, which the Windows host's `hosts` maps to 127.0.0.1."""
    live = live or os.environ["VEYQ_LIVE"]
    origin = urlsplit(live)
    if not origin.hostname:
        sys.exit(f"VEYQ_LIVE is not an origin: {live}")
    port = origin.port or (443 if origin.scheme == "https" else 80)
    forward = ["-R", f"{port}:127.0.0.1:{port}"]
    # a blob's way up or down (a cover, a receipt) goes to the object store's public endpoint, a dev instance's Vite
    # origin (VEYQ_LIVE_STORE, e.g. http://veyq.localhost:8081): its port too, where it is another
    store = urlsplit(os.environ.get("VEYQ_LIVE_STORE", ""))
    if store.hostname:
        store_port = store.port or (443 if store.scheme == "https" else 80)
        if store_port != port:
            forward += ["-R", f"{store_port}:127.0.0.1:{store_port}"]
    return forward, live


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


def generate() -> Path:
    """Writes what the C# is built from but never keeps — the bridge's types and samples, the words, the theme — and
    links the debug DLL; returns the DLL."""
    run("./gradlew", ":bridge:generateBindings", ":bridge:linkDebugSharedMingwX64", ":bridge:jvmTest", "--tests",
        "*WireTest*", cwd=repo_layout.MOBILE)
    run(sys.executable, str(WORDS / "generate_words.py"), "--resw", str(GENERATED / "words" / "windows"))
    run(sys.executable, str(THEME / "generate_theme.py"), "--winui", str(GENERATED / "theme" / "windows"))
    dll = DLL_DIR["debug"] / "veyq.dll"
    if not dll.exists():
        sys.exit(f"no DLL at {dll}")
    return dll


def push_tree(dll: Path) -> None:
    """The C# tree, the generated sources, words.json and the DLL to the Windows host, under their paths here (a
    project's own bin, obj and test results left behind)."""
    archive = SMOKE_OUT.parent / "tree.tar"
    archive.parent.mkdir(parents=True, exist_ok=True)
    archive.write_bytes(tree_tar({
        HERE: "src/mobile/windows",
        GENERATED / "bridge": "src/mobile/build/generated/bridge",
        GENERATED / "words" / "windows": "src/mobile/build/generated/words/windows",
        GENERATED / "theme" / "windows": "src/mobile/build/generated/theme/windows",
        WORDS / "words.json": "src/mobile/words/words.json",
        dll: "bin/veyq.dll",
    }, exclude={"bin", "obj", "TestResults"}))
    copy_to([archive], REMOTE_DIR)


def host_script(*steps: str) -> str:
    """A PowerShell script for the Windows host: the pushed tree unpacked in place of the last one, every project
    restored locked, then [steps] (each stops the script on a non-zero exit) in src/mobile/windows."""
    lines = [
        "$ErrorActionPreference = 'Stop'",
        *(f"$env:{k} = {ps_quote(v)}" for k, v in DOTNET_ENV.items()),
        f"Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(REMOTE_DIR / 'src'))}, {ps_quote(str(REMOTE_DIR / 'bin'))}",
        f"Set-Location {ps_quote(str(REMOTE_DIR))}",
        "tar.exe -xf tree.tar; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }",
        "Remove-Item -Force tree.tar",
        f"Set-Location {ps_quote(str(REMOTE_MOBILE / 'windows'))}",
        "Write-Output ('+ dotnet ' + (dotnet --version))",
        *(f"dotnet restore --locked-mode {p}; if ($LASTEXITCODE -ne 0) {{ exit $LASTEXITCODE }}" for p in PROJECTS),
    ]
    for step in steps:
        lines += [f"Write-Output {ps_quote('+ ' + step.splitlines()[0])}", step,
                  "if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }"]
    return "\n".join(lines + ["exit 0"])


NATIVE = f"-p:VeyqNative={ps_quote(str(REMOTE_DIR / 'bin' / 'veyq.dll'))}"
BUILD_APP = f"dotnet build {APP} -c Debug --no-restore {NATIVE}"
BUILD_DRIVE = "\n".join([
    f"dotnet build {DRIVE} -c Release --no-restore; if ($LASTEXITCODE -ne 0) {{ exit $LASTEXITCODE }}",
    # the tasks run it from one place; it is never running between two drives
    f"Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(REMOTE_DRIVE))}",
    f"Copy-Item -Recurse -Path {ps_quote(str(REMOTE_MOBILE / 'windows' / DRIVE_OUT))} -Destination {ps_quote(str(REMOTE_DRIVE))}",
])


def build() -> int:
    dll = generate()
    for project in PROJECTS:
        dotnet("restore", "--locked-mode", project)
    gated = [deprecation_gate(project) for project in PROJECTS]
    if not all(gated):
        return 1
    dotnet("test", "--project", BRIDGE_TESTS, "--no-restore")
    push_tree(dll)
    windows = REMOTE_MOBILE / "windows"
    return powershell(host_script(
        BUILD_APP,
        BUILD_DRIVE,
        "\n".join([
            f"$env:VEYQ_SAMPLES = {ps_quote(str(REMOTE_MOBILE / 'build' / 'generated' / 'bridge' / 'samples'))}",
            f"dotnet test --project {BRIDGE_TESTS} --no-restore --arch x64 {NATIVE}",
        ]),
        "\n".join([
            f"$env:VEYQ_PRI = {ps_quote(str(windows / APP_OUT / f'{APP_NAME}.pri'))}",
            f"$env:VEYQ_WORDS_JSON = {ps_quote(str(REMOTE_MOBILE / 'words' / 'words.json'))}",
            f"dotnet test --project {APP_TESTS} --no-restore --arch x64",
        ]),
    ))


def run_task(task: str, wait_for: PureWindowsPath, seconds: int) -> int:
    """Runs the scheduled [task] in the logged-on session and waits until [wait_for] exists there (at most [seconds])."""
    return powershell("\n".join([
        f"schtasks.exe /Run /TN {task} | Out-Null",
        f"if ($LASTEXITCODE -ne 0) {{ Write-Output 'veyq: the scheduled task {task} did not run: register it once (run.py register)'; exit 3 }}",
        f"$deadline = (Get-Date).AddSeconds({seconds})",
        f"while (-not (Test-Path {ps_quote(str(wait_for))})) {{",
        f"  if ((Get-Date) -gt $deadline) {{ Write-Output 'veyq: {task} wrote no {wait_for.name} in {seconds} s'; exit 4 }}",
        "  Start-Sleep -Milliseconds 200",
        "}",
        "exit 0",
    ]), quiet=True)


def launch(env: dict[str, str]) -> int:
    """Starts the app in <dir>\\app in the logged-on session with [env] (through a file Veyq.Drive deletes on reading)."""
    start = REMOTE_RUN / "app.json"
    started = REMOTE_RUN / "app.json.started"
    SMOKE_OUT.parent.mkdir(parents=True, exist_ok=True)
    local = SMOKE_OUT.parent / "app.json"
    local.write_text(json.dumps({"exe": str(REMOTE_APP / f"{APP_NAME}.exe"), "env": env}))
    powershell(f"New-Item -ItemType Directory -Force -Path {ps_quote(str(REMOTE_RUN))} | Out-Null; "
               f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(started))}", quiet=True)
    try:
        copy_to([local], REMOTE_RUN)
    finally:
        # it may hold the dev principal (--devlogin): never left behind here
        local.unlink(missing_ok=True)
    code = run_task("veyq-app", started, 30)
    if code == 0:
        print("the app runs in the logged-on session", flush=True)
    else:
        # the launch read nothing: its file goes, whatever it held
        powershell(f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(start))}", quiet=True)
    return code


def dev_principal(live: str, unlock: bool = False, fill: bool = False) -> dict[str, str]:
    """The seeded dev principal as the debug build's VEYQ_DEV_* (Linux's and DevLogin.swift's names): the server is
    [live], the rest the env file's (VEYQ_ENV_FILE, else `.run/.env`). With [unlock] the password alone, as
    VEYQ_DEV_UNLOCK_PASSWORD (Android's `--unlock`); with [fill] the app fills its sign-in and leaves "Stay signed in"
    and the button to the pass (VEYQ_DEV_FILL). Nothing of it is printed; it reaches the Windows host only inside the
    launch file, which Veyq.Drive deletes on reading."""
    values: dict[str, str] = {}
    for line in env_file().read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            values[k.strip()] = v.strip().strip('"').strip("'")
    if unlock:
        return {"VEYQ_DEV_UNLOCK_PASSWORD": values["VEYQ_PASSWORD"]}
    principal = {"VEYQ_DEV_SERVER": live, "VEYQ_DEV_EMAIL": values["VEYQ_EMAIL"], "VEYQ_DEV_PASSWORD": values["VEYQ_PASSWORD"],
                 "VEYQ_DEV_SECRET_KEY": values["VEYQ_SECRET_KEY"]}
    return principal | ({"VEYQ_DEV_FILL": "1"} if fill else {})


def run_app(width: int | None, height: int | None, devlogin: bool = False, unlock: bool = False, fill: bool = False) -> int:
    live_origin = os.environ.get("VEYQ_LIVE", "http://veyq.localhost:8080")
    secrets = dev_principal(live_origin, unlock, fill) if devlogin else {}
    dll = generate()
    for project in PROJECTS:
        dotnet("restore", "--locked-mode", project)
    push_tree(dll)
    code = powershell(host_script(
        BUILD_APP,
        BUILD_DRIVE,
        "\n".join([
            f"Get-Process -Name {APP_NAME} -ErrorAction SilentlyContinue | Stop-Process -Force",
            "Start-Sleep -Milliseconds 500",
            # A file still held open (an app the stop had not ended yet) survived the remove once, and Copy-Item into the
            # folder left behind then nested the build one level down (`app\win-x64\`): no Veyq.App.exe where the launch
            # looks (measured 2026-11-11). So: retry the remove, refuse a folder that stays, copy the build's contents.
            f"$app = {ps_quote(str(REMOTE_APP))}",
            "foreach ($i in 1..10) { Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path $app; "
            "if (-not (Test-Path $app)) { break }; Start-Sleep -Milliseconds 500 }",
            "if (Test-Path $app) { Write-Error \"$app could not be removed: a file in it is still open\"; exit 1 }",
            "New-Item -ItemType Directory -Path $app | Out-Null",
            f"Copy-Item -Recurse -Path (Join-Path {ps_quote(str(REMOTE_MOBILE / 'windows' / APP_OUT))} '*') -Destination $app",
        ]),
    ))
    if code != 0:
        return code
    env = {k: os.environ[k] for k in ("VEYQ_THEME", "VEYQ_LANGUAGE") if os.environ.get(k)} | secrets
    code = launch(env)
    if code != 0:
        return code
    if width and height:
        code = drive([f"size {width} {height}"])
        if code != 0:
            return code
    forward, live = live_forward(live_origin)
    print(f"veyq: the tunnel to {live} is held until this ends (Ctrl+C); the app keeps running", flush=True)
    try:
        return subprocess.run(ssh_base(forward) + ["-N"]).returncode
    except KeyboardInterrupt:
        return 0


def fetch(remote: str, local: Path) -> bool:
    """[remote] (a path on the Windows host) to [local]; False where it is not there."""
    local.parent.mkdir(parents=True, exist_ok=True)
    done = subprocess.run(["scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-i", str(SSH_KEY),
                           f"{SSH_TARGET}:{remote.replace(chr(92), '/')}", str(local)], capture_output=True)
    return done.returncode == 0


def drive(steps: list[str]) -> int:
    """Runs [steps] through Veyq.Drive in the logged-on session; prints the app's log, then what each step saw. A
    `shot <name>` step's PNG lands in SHOTS here."""
    shots = []
    lines = []
    for step in steps:
        if step.split(" ", 1)[0] == "shot":
            name = PureWindowsPath(step.split(" ", 1)[1].strip()).name
            shots.append(name)
            lines.append(f"shot {REMOTE_RUN / 'shots' / name}")
        else:
            lines.append(step)
    local = SMOKE_OUT.parent / "steps.txt"
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text("\n".join(lines) + "\n", encoding="utf-8")
    steps_file = REMOTE_RUN / "steps.txt"
    powershell(f"New-Item -ItemType Directory -Force -Path {ps_quote(str(REMOTE_RUN))} | Out-Null; Remove-Item -Force "
               f"-ErrorAction SilentlyContinue -Path {ps_quote(str(steps_file) + '.out')}, {ps_quote(str(steps_file) + '.done')}",
               quiet=True)
    copy_to([local], REMOTE_RUN)
    code = run_task("veyq-drive", PureWindowsPath(str(steps_file) + ".done"), 300)
    log = SMOKE_OUT.parent / "log.txt"
    print("--- the app's log", flush=True)
    print(log.read_text(encoding="utf-8-sig") if fetch(REMOTE_LOG, log) else "(none)", flush=True)
    if code != 0:
        return code
    out = SMOKE_OUT.parent / "steps.out"
    if not fetch(str(steps_file) + ".out", out):
        sys.exit("veyq: the drive wrote no output")
    print("--- drive", flush=True)
    print(out.read_text(encoding="utf-8"), flush=True)
    failed = "FAILED:" in out.read_text(encoding="utf-8")
    for name in shots:
        if fetch(str(REMOTE_RUN / "shots" / name), SHOTS / name):
            print(f"shot: {SHOTS / name}", flush=True)
        elif not failed:
            sys.exit(f"veyq: no shot {name}")
    return 1 if failed else 0


def hello_probe(timeout: int, check: bool) -> int:
    run("./gradlew", ":core:linkDebugTestMingwX64", cwd=repo_layout.MOBILE)
    if not TEST_BINARY.exists():
        sys.exit(f"no test binary at {TEST_BINARY}")
    # Veyq.Drive's launch takes the binary's arguments ("args"), which an older build there ignores: rebuilt first
    dll = generate()
    for project in PROJECTS:
        dotnet("restore", "--locked-mode", project)
    push_tree(dll)
    code = powershell(host_script(BUILD_DRIVE))
    if code != 0:
        return code
    remote = REMOTE_DIR / "probe"
    outcome = remote / "hello-probe.txt"
    powershell(f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(outcome))}", quiet=True)
    copy_to([TEST_BINARY], remote)
    start = REMOTE_RUN / "app.json"
    started = REMOTE_RUN / "app.json.started"
    local = SMOKE_OUT.parent / "app.json"
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text(json.dumps({"exe": str(remote / TEST_BINARY.name), "args": ["--ktest_filter=*HelloProbe*"],
                                 "env": {"VEYQ_HELLO_PROBE": str(outcome)} | ({"VEYQ_HELLO_PROBE_CHECK": "1"} if check else {})}))
    powershell(f"New-Item -ItemType Directory -Force -Path {ps_quote(str(REMOTE_RUN))} | Out-Null; "
               f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(started))}", quiet=True)
    copy_to([local], REMOTE_RUN)
    local.unlink(missing_ok=True)
    # a Veyq.Drive just rebuilt took 33 s to start the binary (measured 2026-11-11): the app's 30 s is too short here
    code = run_task("veyq-app", started, 120)
    if code != 0:
        powershell(f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(start))}", quiet=True)
        return code
    # Its steps wait for a person at the console (the seal's prompt waited 649 s once, 2026-11-11), so the wait ends when
    # the probe does — `done`, or its process gone — and the timeout is only a safety net. The outcome is fetched last.
    pid_file = SMOKE_OUT.parent / "hello-probe.pid"
    pid = pid_file.read_text().strip() if fetch(str(started), pid_file) else ""
    alive = f"if (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ exit 0 }} else {{ exit 1 }}" if pid.isdigit() else "exit 0"
    print(f"veyq: the probe (pid {pid or '?'}) runs in the logged-on session; waiting until it ends (at most {timeout} s)",
          flush=True)
    local_outcome = SMOKE_OUT.parent / "hello-probe.txt"
    local_outcome.unlink(missing_ok=True)
    shown = 0

    def show() -> list[str]:
        nonlocal shown
        lines = local_outcome.read_text(encoding="utf-8").splitlines() if fetch(str(outcome), local_outcome) else []
        for line in lines[shown:]:
            print(f"probe: {line}", flush=True)
        shown = max(shown, len(lines))
        return lines

    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        lines = show()
        if lines and lines[-1] == "done":
            return 0
        if powershell(alive, quiet=True) != 0:
            show()  # what it wrote before it ended
            print(f"veyq: the probe ended without `done`; its outcome: {local_outcome}", flush=True)
            return 4
        time.sleep(3)
    show()
    print(f"veyq: the probe still runs after {timeout} s; what it wrote so far: {local_outcome}", flush=True)
    return 4


def register() -> int:
    """Registers the scheduled tasks Veyq.Drive runs in the logged-on session (interactive only, as the ssh user, on
    demand: no trigger). The host owner's step: a key-authenticated ssh logon can register them, but it is theirs to."""
    lines = [
        # the account as Windows names it: over ssh $env:USERDOMAIN reads WORKGROUP, which maps to no SID (0x80070534)
        "$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name",
        "$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited",
        "$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries "
        "-ExecutionTimeLimit (New-TimeSpan -Hours 0) -MultipleInstances Parallel",
    ]
    for task, mode in TASKS.items():
        file = REMOTE_RUN / ("app.json" if mode == "--launch" else "steps.txt")
        lines += [
            f"$action = New-ScheduledTaskAction -Execute {ps_quote(str(REMOTE_DRIVE / (DRIVE + '.exe')))} "
            f"-Argument {ps_quote(f'{mode} {file}')}",
            f"Register-ScheduledTask -TaskName {task} -Action $action -Principal $principal -Settings $settings "
            f"-Description 'veyq: run on demand by src/mobile/windows/run.py in the logged-on session' -Force | "
            "Select-Object TaskName, State | Format-Table -AutoSize | Out-String",
        ]
    return powershell("\n".join(lines))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    verbs = parser.add_subparsers(dest="verb", required=True)
    t = verbs.add_parser("test", help="link the core's Windows test binary and run it on the Windows host")
    t.add_argument("--tests", help="Kotlin/Native --ktest_filter pattern, e.g. '*LiveSignInTest*'")
    verbs.add_parser("bridge", help="link the bridge's DLL, check its export table and run the C smoke on the Windows host")
    verbs.add_parser("build", help="generate the C#'s sources, gate the packages, build and test here and on the Windows host")
    r = verbs.add_parser("run", help="build the app, start it in the Windows host's logged-on session, hold the tunnel")
    r.add_argument("--width", type=int)
    r.add_argument("--height", type=int)
    r.add_argument("--devlogin", action="store_true", help="sign the debug build in as the seeded principal of the env file")
    r.add_argument("--fill", action="store_true", help="with --devlogin: only fill the sign-in; the pass presses it")
    r.add_argument("--unlock", action="store_true", help="with --devlogin: the password alone, for a password-trusted unlock")
    d = verbs.add_parser("drive", help="drive the running app by UI Automation (Veyq.Drive's steps) and print what it saw")
    d.add_argument("steps", nargs="+", help="each a step: tree, invoke <id>, select <id>, set <id> <text>, key <keys>, "
                                            "type <id> <text>, press <id> [keys], size <w> <h>, maximize, wait <id> [s], sleep <ms>, shot <name>.png")
    h = verbs.add_parser("hello-probe", help="run the Hello probe in the logged-on session: the person enters the PIN, then cancels")
    h.add_argument("--timeout", type=int, default=3600)
    h.add_argument("--check", action="store_true", help="stop before the first key: the launch and Hello's availability, no prompt")
    verbs.add_parser("register", help="register the scheduled tasks veyq-app and veyq-drive on the Windows host (owner)")
    args = parser.parse_args()
    if args.verb == "test":
        sys.exit(test(args.tests))
    if args.verb == "bridge":
        sys.exit(bridge())
    if args.verb == "build":
        sys.exit(build())
    if args.verb == "run":
        if (args.fill or args.unlock) and not args.devlogin:
            sys.exit("--fill and --unlock go with --devlogin")
        if args.fill and args.unlock:
            sys.exit("--fill and --unlock exclude each other")
        sys.exit(run_app(args.width, args.height, args.devlogin, args.unlock, args.fill))
    if args.verb == "drive":
        sys.exit(drive(args.steps))
    if args.verb == "hello-probe":
        sys.exit(hello_probe(args.timeout, args.check))
    if args.verb == "register":
        sys.exit(register())


if __name__ == "__main__":
    main()
