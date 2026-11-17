#!/usr/bin/env python3
"""The one entry point for everything Windows of the veyq app (ADR 0136 decision 5), run on this host (a Mac or Linux):
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
                             de-DE) from this host's environment. VEYQ_DEV_SCAN=1 shows its QR harness instead of the
                             session (no sign-in, no tunnel held); VEYQ_DEV_CAMERA=<image on this host> is its test
                             camera, copied to the host beside the launch file. VEYQ_DEV_SETTINGS=1 shows the account
                             Settings over a state of its own instead (no sign-in, no tunnel held). VEYQ_DEV_CAPTURABLE=1
                             keeps the window capturable while a secret shows (ScreenSecrets), for a pass's shots of the
                             sign-in, the unlock and the password asks. `--devlogin` hands the debug build the seeded
                             principal of the env file (VEYQ_ENV_FILE, else `.run/.env`) as VEYQ_DEV_*, inside the
                             launch file Veyq.Drive deletes on reading — never on a command line —, and it signs in
                             through its own sign-in once (DevLogin.cs). `--fill` only fills the sign-in, for the pass to
                             press Sign in; `--unlock` hands the password alone, which the unlock of a password-trusted
                             account takes, and Manage's way back to the password fills its field with it once.
  drive <step>...            runs Veyq.Drive's steps (its Program.cs: tree, invoke, select, set, type, key, press,
                             scroll, wheel, size, maximize, wait, sleep, shot) against the running app through the scheduled task
                             `veyq-drive`, and prints the app's log first, then what each step saw. `shot <name>.png`
                             (and `screen`'s, `hovershot`'s)
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
  package [--sign [PFX]] [--smoke]
                             the MSIX (ADR 0139 decisions 6 and 7): generates as `build` does, but links the bridge's
                             release DLL; fills Veyq/Package.appxmanifest.in — the identity from
                             src/mobile/desktop-identity.json (VEYQ_MSIX_PUBLISHER overrides its publisher, which must
                             equal the signing certificate's subject), the version from the tree as the pipeline counts it
                             (`<major>.<minor>.0.0`), the device family's from the project — into the generated sources;
                             gates and tests here as `build` does, then on the Windows host builds Veyq.Drive, runs
                             Veyq.Bridge.Tests and Veyq.Tests in Release, and builds the app in Release as an unsigned,
                             self-contained x64 MSIX through the Windows App SDK's MSBuild tooling, fetched to
                             src/mobile/build/windows/package/veyq-<version>-x64.msix. `--sign` signs a copy there
                             (`-signed.msix`) with the pinned Microsoft.Windows.SDK.BuildTools' signtool: PFX (a file on
                             this host, its password in VEYQ_MSIX_PFX_PASSWORD) is copied over for the signing and deleted
                             after it; without one, a development certificate of the publisher's subject is made on the
                             Windows host (CurrentUser\\My, its key never leaving it) and trusted there (LocalMachine\\
                             TrustedPeople), kept for the next run until `drop-dev-cert`. Either way red unless the signed
                             copy's AppxBlockMap.xml is byte-identical to the unsigned one's (`makeappx unpack` of both):
                             signing changes no payload byte. `--smoke` (with --sign) installs the signed copy, starts it
                             by its AUMID (Veyq.Drive's launch), waits until its log says `session SignIn`, and
                             uninstalls it, red if anything of the package stays — all in the logged-on session
                             (`veyq-app`): deployment from ssh's session 0 is refused.
                             `--prebuilt` takes the release DLL and the generated sources `inputs` left in this tree
                             instead of making them (CI's `msix` job, after `app-inputs` on macOS).
  inputs                     what `package` makes on this host before the Windows host's part, alone: the bridge's
                             release DLL, its C# and samples, the words and the theme with the MSIX's images (Quick Look:
                             a Mac). CI's `app-inputs` runs it and hands the files to `package --prebuilt`.
  drop-dev-cert              removes `package --sign`'s development certificate from both stores on the Windows host.
  register                   registers the two scheduled tasks, interactive only, as the user ssh logs in as: they run
                             on demand in the logged-on session, which an ssh command (session 0) cannot reach. Run once
                             per Windows host, by its owner (a persistent change on that machine).
The Windows host: `VEYQ_WINDOWS_SSH` (default `kevin@192.168.64.8`, the test VM of docs/crosscutting/native-apps.md;
`local` when this host is the Windows host — CI's `msix` job on a GitHub Windows runner: PowerShell, copies and the
launch run here, with no ssh and no scheduled task, so this host's own session must be an interactive one),
its key `VEYQ_WINDOWS_KEY` (default `~/.ssh/veyq_vms_ed25519`), and a directory there `VEYQ_WINDOWS_DIR` (default
`F:\\veyq`, the VM's Dev Drive) which this script owns. A pass beside another names its own directory and its own
scheduled tasks with `VEYQ_WINDOWS_TASKS=<suffix>` (`veyq-app-<suffix>`, `veyq-drive-<suffix>`, which `register` sets up
against that directory; the pass unregisters them after). The host is x64 or ARM64: the binary is x64 and runs under
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
# The web's Emergency Kit fixtures, pushed with the tree for Veyq.Tests' KitDrawingTest (VEYQ_KIT_FIXTURES).
KIT_FIXTURES = "src/web/src/features/account/emergencyKit/__fixtures__"
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
# A parallel pass names a directory and tasks of its own (`VEYQ_WINDOWS_TASKS=<suffix>`: `veyq-app-<suffix>` and
# `veyq-drive-<suffix>`, registered by `register` against that directory's drive and run files), so it never takes the
# default tasks' F:\veyq from another pass.
TASK_SUFFIX = os.environ.get("VEYQ_WINDOWS_TASKS", "")
APP_TASK = "veyq-app" + (f"-{TASK_SUFFIX}" if TASK_SUFFIX else "")
DRIVE_TASK = "veyq-drive" + (f"-{TASK_SUFFIX}" if TASK_SUFFIX else "")
TASKS = {APP_TASK: "--launch", DRIVE_TASK: "--drive"}  # each scheduled task and Veyq.Drive's mode it runs
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
REMOTE_LOG = "AppData/Local/veyq/log.txt"  # the unpackaged app's log (Log.cs, AppData.cs), from the ssh user's home
# the packaged app's log under %LOCALAPPDATA%: its package's own LocalState (AppData.cs), never REMOTE_LOG's
PACKAGE_LOG = "Packages/{family}/LocalState/log.txt"
# `local`: this host is the Windows host (CI's msix job). Its directory must be one of its own, never this tree: a verb
# deletes and unpacks `src` and `bin` there.
LOCAL = SSH_TARGET == "local"
if LOCAL and (REMOTE_DIR == PureWindowsPath(ROOT) or PureWindowsPath(ROOT) in PureWindowsPath(REMOTE_DIR).parents
              or PureWindowsPath(REMOTE_DIR) in PureWindowsPath(ROOT).parents):
    sys.exit(f"VEYQ_WINDOWS_DIR ({REMOTE_DIR}) overlaps this tree ({ROOT}): name a directory of its own")
# the console code page PowerShell writes in: over ssh on the (German) test VM CP850, here the console's OEM one
CODE_PAGE = "oem" if LOCAL else "cp850"


def run(*args: str, cwd: Path | None = None, env: dict[str, str] | None = None) -> None:
    print("+ " + " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True, env=os.environ | (env or {}))


def ssh_base(forward: list[str] | None = None) -> list[str]:
    return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-o", "ExitOnForwardFailure=yes",
            "-i", str(SSH_KEY), *(forward or []), SSH_TARGET]


LINE_BOUND = 8191 - len(r"C:\Windows\system32\cmd.exe /c ")  # an encoded command's longest line over ssh


def powershell(script: str, forward: list[str] | None = None, quiet: bool = False) -> int:
    """Runs [script] in PowerShell on the Windows host and streams what it prints, decoded from the console's code page.
    The first connection after the host idled can time out once (ssh's 255 before anything ran): it is tried again."""
    encoded = base64.b64encode(("$ProgressPreference = 'SilentlyContinue'\n" + script).encode("utf-16-le")).decode()
    if LOCAL:
        # no cmd.exe between: CreateProcess's line holds 32767 characters
        if not quiet:
            print(f"+ powershell <{len(script.splitlines())} lines>", flush=True)
        done = subprocess.run([POWERSHELL_EXE, "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        sys.stdout.write(done.stdout.decode(CODE_PAGE, errors="replace").replace("\r\n", "\n"))
        sys.stdout.flush()
        return done.returncode
    command = ssh_base(forward) + ["powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded]
    # cmd.exe, which OpenSSH's default shell on Windows is, takes 8191 characters a line, its own `cmd.exe /c` call
    # included: a command of 8190 characters was refused ("Die Befehlszeile ist zu lang", measured 2026-11-13), so the
    # bound leaves room for the call OpenSSH wraps it in
    if len(" ".join(command[command.index("powershell"):])) > LINE_BOUND:
        sys.exit(f"veyq: a script of {len(script)} characters is too long for one ssh command line: split it")
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
    if LOCAL:
        Path(str(remote_dir)).mkdir(parents=True, exist_ok=True)
        for f in files:
            print(f"+ copy {f} {remote_dir}", flush=True)
            shutil.copy2(f, Path(str(remote_dir)) / (as_name or f.name))
        return
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
    # the QR reader's fixtures (QrFixtures, read through VEYQ_REPO_ROOT at their repository path)
    qr_fixtures = "src/mobile/core/src/commonTest/resources/qr"
    copy_to(sorted((ROOT / qr_fixtures).glob("*.pgm")), remote / "repo" / PureWindowsPath(qr_fixtures))
    env = {"VEYQ_VECTORS": str(remote / VECTORS.name), "VEYQ_NORMALIZATION_TEST": str(remote / NORMALIZATION_TEST.name),
           "VEYQ_REPO_ROOT": str(remote / "repo")}
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


def generate(kind: str = "debug") -> Path:
    """Writes what the C# is built from but never keeps — the bridge's types and samples, the words, the theme — and
    links the DLL of [kind] (`debug`, `release`: the package's); returns the DLL."""
    link = f":bridge:link{kind.capitalize()}SharedMingwX64"
    run("./gradlew", ":bridge:generateBindings", link, ":bridge:jvmTest", "--tests", "*WireTest*", cwd=repo_layout.MOBILE)
    run(sys.executable, str(WORDS / "generate_words.py"), "--resw", str(GENERATED / "words" / "windows"))
    run(sys.executable, str(THEME / "generate_theme.py"), "--winui", str(GENERATED / "theme" / "windows"))
    dll = DLL_DIR[kind] / "veyq.dll"
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
        **({GENERATED / "msix": "src/mobile/build/generated/msix"} if (GENERATED / "msix").is_dir() else {}),
        WORDS / "words.json": "src/mobile/words/words.json",
        # the web's Emergency Kit fixtures (fake data), which KitDrawingTest draws
        ROOT / KIT_FIXTURES: KIT_FIXTURES,
        dll: "bin/veyq.dll",
    }, exclude={"bin", "obj", "TestResults"}))
    copy_to([archive], REMOTE_DIR)


def host_script(*steps: str, unpack: bool = True) -> str:
    """A PowerShell script for the Windows host: the pushed tree unpacked in place of the last one, every project
    restored locked, then [steps] (each stops the script on a non-zero exit) in src/mobile/windows. Without [unpack]
    only the steps, in the tree an earlier script unpacked: an encoded command is held to cmd.exe's 8191 characters
    ("Die Befehlszeile ist zu lang", about 3000 of script), so a long run goes as several scripts."""
    lines = [
        "$ErrorActionPreference = 'Stop'",
        *(f"$env:{k} = {ps_quote(v)}" for k, v in DOTNET_ENV.items()),
    ]
    if unpack:
        lines += [
            f"Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(REMOTE_DIR / 'src'))}, {ps_quote(str(REMOTE_DIR / 'bin'))}",
            f"Set-Location {ps_quote(str(REMOTE_DIR))}",
            "tar.exe -xf tree.tar; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }",
            "Remove-Item -Force tree.tar",
        ]
    lines.append(f"Set-Location {ps_quote(str(REMOTE_MOBILE / 'windows'))}")
    if unpack:
        lines += [
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
            f"$env:VEYQ_KIT_FIXTURES = {ps_quote(str(REMOTE_DIR / PureWindowsPath(KIT_FIXTURES)))}",
            f"dotnet test --project {APP_TESTS} --no-restore --arch x64",
        ]),
    ))


IDENTITY = json.loads((repo_layout.MOBILE / "desktop-identity.json").read_text())  # ADR 0139 decision 12
MSIX_TEMPLATE = HERE / APP / "Package.appxmanifest.in"
PACKAGE_OUT = repo_layout.MOBILE / "build" / "windows" / "package"  # the MSIX here, fetched from the host
REMOTE_PACKAGE = REMOTE_DIR / "package"
# the package build's output on the Windows host, below src/mobile/windows: the .pri Veyq.Tests reads in Release
APP_RELEASE_OUT = PureWindowsPath(APP, "bin", "Release", "net10.0-windows10.0.26100.0", "win-x64")
DEV_CERT_NAME = "veyq development (run.py package)"  # the development certificate's FriendlyName on the Windows host
# What the packaged app's log says once it shows its sign-in (MainWindow's ShowGate): the smoke's end.
SMOKE_SIGN_IN = "session SignIn"


def msix_publisher() -> str:
    """The MSIX's Publisher: VEYQ_MSIX_PUBLISHER (a self-builder's own certificate subject, ADR 0139 decision 7), else
    desktop-identity.json's."""
    return os.environ.get("VEYQ_MSIX_PUBLISHER") or IDENTITY["msixPublisher"]


def msix_version() -> tuple[str, str]:
    """The tree's `<major>.<minor>` as the pipeline counts it (release_assets.tree_release, the one count) and the
    package's four-part version `<major>.<minor>.0.0` (each part at most 65535; the revision 0, as a store requires)."""
    import release_assets  # noqa: E402 - scripts/, on sys.path above
    version = release_assets.tree_release(ROOT)
    if not re.fullmatch(r"[0-9]+\.[0-9]+", version) or any(int(part) > 65535 for part in version.split(".")):
        sys.exit(f"the version {version} is no <major>.<minor> of two parts of at most 65535 each")
    return version, f"{version}.0.0"


def target_platform() -> tuple[str, str]:
    """The app project's TargetPlatformMinVersion and TargetPlatformVersion, as MSBuild evaluates them here: the
    device family's MinVersion and MaxVersionTested."""
    done = subprocess.run(["dotnet", "msbuild", APP, "-p:Configuration=Release", "-p:VeyqPackage=true",
                           "-getProperty:TargetPlatformMinVersion", "-getProperty:TargetPlatformVersion"],
                          cwd=HERE, env=os.environ | DOTNET_ENV, capture_output=True, text=True, check=True)
    props = json.loads(done.stdout)["Properties"]
    return props["TargetPlatformMinVersion"], props["TargetPlatformVersion"]


def fill_manifest(four_part: str) -> Path:
    """Package.appxmanifest.in filled into the generated sources (src/mobile/build/generated/msix/), where the package
    build reads it; red on a placeholder left over."""
    low, tested = target_platform()
    fill = {"@APP_ID@": IDENTITY["appId"], "@PUBLISHER@": msix_publisher(), "@VERSION@": four_part,
            "@MIN_VERSION@": low, "@MAX_VERSION_TESTED@": tested}
    text = MSIX_TEMPLATE.read_text(encoding="utf-8")
    for key, value in fill.items():
        text = text.replace(key, value)
    left = sorted(set(re.findall(r"@[A-Z_]+@", text)))
    if left:
        sys.exit(f"{MSIX_TEMPLATE.name} has placeholders run.py does not fill: {', '.join(left)}")
    # well-formed here: the package build's manifest task hides an XML error behind an assembly it cannot load
    # (System.Security.Permissions, under the .NET SDK's MSBuild; a `--` in a comment, measured 2026-11-13)
    import xml.etree.ElementTree as ET
    try:
        ET.fromstring(text)
    except ET.ParseError as e:
        sys.exit(f"{MSIX_TEMPLATE.name} is no well-formed XML: {e}")
    out = GENERATED / "msix" / "Package.appxmanifest"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(f"veyq: {out.relative_to(repo_layout.MOBILE)}: {IDENTITY['appId']} {four_part}, {fill['@PUBLISHER@']}, "
          f"Windows.Desktop {low} to {tested}", flush=True)
    return out


def build_tools() -> str:
    """The pinned Microsoft.Windows.SDK.BuildTools' version (Directory.Packages.props): its makeappx and signtool."""
    props = (HERE / "Directory.Packages.props").read_text()
    m = re.search(r'Include="Microsoft\.Windows\.SDK\.BuildTools" Version="\[([0-9.]+)\]"', props)
    if not m:
        sys.exit("Directory.Packages.props pins no Microsoft.Windows.SDK.BuildTools")
    return m.group(1)


def tools_script() -> list[str]:
    """PowerShell lines that set $tools to the pinned build tools' directory for the host's architecture, in the NuGet
    cache the locked restore filled (no Windows SDK is installed)."""
    return [
        # NuGet's global packages folder: NUGET_PACKAGES where a host sets it (a runner may), else the user's default
        "$nuget = if ($env:NUGET_PACKAGES) { $env:NUGET_PACKAGES } else { Join-Path $env:USERPROFILE '.nuget/packages' }",
        f"$pkg = Join-Path $nuget {ps_quote('microsoft.windows.sdk.buildtools/' + build_tools() + '/bin')}",
        "$sdk = @(Get-ChildItem -Directory $pkg)",
        "if ($sdk.Count -ne 1) { Write-Output \"veyq: expected one SDK version under $pkg, found $($sdk.Count)\"; exit 1 }",
        "$tools = Join-Path $sdk[0].FullName $(if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x64' })",
    ]


# What `inputs` writes beside the DLL, each a directory `package --prebuilt` refuses to go without.
GENERATED_INPUTS = (GENERATED / "bridge" / "samples", GENERATED / "words" / "windows", GENERATED / "theme" / "windows" / "msix")


def package(sign: str | None, smoke: bool, prebuilt: bool = False) -> int:
    version, four_part = msix_version()
    name = f"veyq-{version}-x64"
    dll = DLL_DIR["release"] / "veyq.dll" if prebuilt else generate("release")
    missing = [p for p in (dll, *GENERATED_INPUTS) if not p.exists()]
    if missing:
        sys.exit(f"--prebuilt finds no {', '.join(str(p.relative_to(ROOT)) for p in missing)}: run `run.py inputs` first"
                 if prebuilt else f"no {', '.join(map(str, missing))}")
    fill_manifest(four_part)
    for project in PROJECTS:
        dotnet("restore", "--locked-mode", project)
    if not all([deprecation_gate(project) for project in PROJECTS]):
        return 1
    dotnet("test", "--project", BRIDGE_TESTS, "-c", "Release", "--no-restore")
    push_tree(dll)
    windows = REMOTE_MOBILE / "windows"
    appx_dir = REMOTE_PACKAGE / "AppPackages"
    msix = REMOTE_PACKAGE / f"{name}.msix"
    code = powershell(host_script(
        BUILD_DRIVE,
        "\n".join([
            f"$env:VEYQ_SAMPLES = {ps_quote(str(REMOTE_MOBILE / 'build' / 'generated' / 'bridge' / 'samples'))}",
            f"dotnet test --project {BRIDGE_TESTS} -c Release --no-restore --arch x64 {NATIVE}",
        ]),
    )) or powershell(host_script(
        "\n".join([
            f"Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(REMOTE_PACKAGE))}",
            f"dotnet build {APP} -c Release --no-restore {NATIVE} -p:VeyqPackage=true "
            f"-p:AppxPackageDir={ps_quote(str(appx_dir) + chr(92))}",
        ]),
        unpack=False,
    )) or powershell(host_script(
        "\n".join([
            # the package's own .pri: a packaged build names it resources.pri (an unpackaged one <assembly>.pri), and
            # a .pri that is not there reads as no words at all
            f"$env:VEYQ_PRI = {ps_quote(str(windows / APP_RELEASE_OUT / 'resources.pri'))}",
            "if (-not (Test-Path $env:VEYQ_PRI)) { Write-Output \"veyq: no $env:VEYQ_PRI\"; exit 1 }",
            f"$env:VEYQ_WORDS_JSON = {ps_quote(str(REMOTE_MOBILE / 'words' / 'words.json'))}",
            f"$env:VEYQ_KIT_FIXTURES = {ps_quote(str(REMOTE_DIR / PureWindowsPath(KIT_FIXTURES)))}",
            f"dotnet test --project {APP_TESTS} -c Release --no-restore --arch x64",
        ]),
        "\n".join([
            f"$built = @(Get-ChildItem -Recurse -File -Filter *.msix -Path {ps_quote(str(appx_dir))})",
            "if ($built.Count -ne 1) { Write-Output \"veyq: the build wrote $($built.Count) MSIX packages, not one\"; exit 1 }",
            f"Copy-Item -Path $built[0].FullName -Destination {ps_quote(str(msix))}",
            f"Remove-Item -Recurse -Force -Path {ps_quote(str(appx_dir))}",
            f"Write-Output ('veyq: ' + {ps_quote(str(msix))} + ', ' + (Get-Item {ps_quote(str(msix))}).Length + ' bytes')",
        ]),
        unpack=False,
    ))
    if code != 0:
        return code
    PACKAGE_OUT.mkdir(parents=True, exist_ok=True)
    if not fetch(str(msix), PACKAGE_OUT / msix.name):
        sys.exit(f"veyq: no {msix.name} to fetch from the host")
    print(f"package: {PACKAGE_OUT / msix.name}", flush=True)
    if sign is None:
        return 0
    signed = REMOTE_PACKAGE / f"{name}-signed.msix"
    code = sign_package(msix, signed, sign)
    if code != 0:
        return code
    if not fetch(str(signed), PACKAGE_OUT / signed.name):
        sys.exit(f"veyq: no {signed.name} to fetch from the host")
    print(f"signed: {PACKAGE_OUT / signed.name}", flush=True)
    return smoke_package(signed) if smoke else 0


def sign_package(msix: PureWindowsPath, signed: PureWindowsPath, pfx: str) -> int:
    """Signs a copy of [msix] as [signed] on the Windows host — with [pfx] (a file here, its password in
    VEYQ_MSIX_PFX_PASSWORD), or where it is empty with the host's development certificate, made and trusted there
    once — and holds the two block maps byte-identical (ADR 0139 decision 7)."""
    secrets: list[PureWindowsPath] = []
    if pfx:
        source = Path(pfx).expanduser().resolve()
        if not source.is_file():
            sys.exit(f"--sign names no file: {source}")
        password = os.environ.get("VEYQ_MSIX_PFX_PASSWORD")
        if password is None:
            sys.exit("--sign <pfx> reads the PFX's password from VEYQ_MSIX_PFX_PASSWORD")
        local = PACKAGE_OUT / "signer.password"
        local.write_text(password, encoding="utf-8")
        try:
            copy_to([source], REMOTE_PACKAGE, as_name="signer.pfx")
            copy_to([local], REMOTE_PACKAGE)
        finally:
            local.unlink(missing_ok=True)
        secrets = [REMOTE_PACKAGE / "signer.pfx", REMOTE_PACKAGE / "signer.password"]
        signer = [
            f"$password = Get-Content -Raw -Encoding UTF8 -Path {ps_quote(str(secrets[1]))}",
            f"& (Join-Path $tools 'signtool.exe') sign /q /fd SHA256 /f {ps_quote(str(secrets[0]))} /p $password {ps_quote(str(signed))}",
        ]
    else:
        subject = msix_publisher()
        signer = [
            # the development certificate: made once with the publisher's subject, its key kept in this user's store
            f"$cert = Get-ChildItem Cert:\\CurrentUser\\My | Where-Object {{ $_.Subject -eq {ps_quote(subject)} -and "
            f"$_.FriendlyName -eq {ps_quote(DEV_CERT_NAME)} -and $_.NotAfter -gt (Get-Date).AddDays(1) }} | Select-Object -First 1",
            "if (-not $cert) {",
            f"  $cert = New-SelfSignedCertificate -Type Custom -Subject {ps_quote(subject)} -FriendlyName {ps_quote(DEV_CERT_NAME)} "
            "-KeyUsage DigitalSignature -CertStoreLocation Cert:\\CurrentUser\\My -NotAfter (Get-Date).AddDays(30) "
            "-TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}')",
            "  Write-Output ('veyq: made the development certificate ' + $cert.Thumbprint)",
            "}",
            # trusted for installing on this host only: its public half in the machine's TrustedPeople
            "if (-not (Test-Path (Join-Path Cert:\\LocalMachine\\TrustedPeople $cert.Thumbprint))) {",
            f"  $cer = Join-Path {ps_quote(str(REMOTE_PACKAGE))} 'dev.cer'",
            "  Export-Certificate -Cert $cert -FilePath $cer | Out-Null",
            "  Import-Certificate -FilePath $cer -CertStoreLocation Cert:\\LocalMachine\\TrustedPeople | Out-Null",
            "  Remove-Item -Force -Path $cer",
            "}",
            f"& (Join-Path $tools 'signtool.exe') sign /q /fd SHA256 /sha1 $cert.Thumbprint /s My {ps_quote(str(signed))}",
        ]
    unpacked = REMOTE_PACKAGE / "unpacked"
    script = "\n".join([
        "$ErrorActionPreference = 'Stop'",
        *tools_script(),
        f"Copy-Item -Force -Path {ps_quote(str(msix))} -Destination {ps_quote(str(signed))}",
        "try {",
        *("  " + line for line in signer),
        "  $code = $LASTEXITCODE",
        "} finally {",
        *(f"  Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(f))}" for f in secrets),
        "}",
        "if ($code -ne 0) { Write-Output \"veyq: signtool exited $code\"; exit $code }",
        # the payload unchanged: both packages unpacked by makeappx (which checks each file against its block map),
        # their block maps compared byte for byte
        f"Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(unpacked))}",
        "$maps = @()",
        f"foreach ($p in @({ps_quote(str(msix))}, {ps_quote(str(signed))})) {{",
        f"  $d = Join-Path {ps_quote(str(unpacked))} ([IO.Path]::GetFileNameWithoutExtension($p))",
        "  & (Join-Path $tools 'makeappx.exe') unpack /o /p $p /d $d | Out-Null",
        "  if ($LASTEXITCODE -ne 0) { Write-Output \"veyq: makeappx unpack $p exited $LASTEXITCODE\"; exit 1 }",
        "  $maps += (Get-FileHash -Algorithm SHA256 -Path (Join-Path $d 'AppxBlockMap.xml')).Hash",
        "}",
        f"$sig = Test-Path (Join-Path {ps_quote(str(unpacked / signed.stem))} 'AppxSignature.p7x')",
        f"Remove-Item -Recurse -Force -Path {ps_quote(str(unpacked))}",
        "if (-not $sig) { Write-Output 'veyq: the signed copy carries no AppxSignature.p7x'; exit 1 }",
        "if ($maps[0] -ne $maps[1]) { Write-Output \"veyq: signing changed the block map ($($maps[0]) -> $($maps[1])): the payload is not the one built\"; exit 1 }",
        "Write-Output ('veyq: signed; AppxBlockMap.xml byte-identical in both (sha256 ' + $maps[0] + ')')",
        "exit 0",
    ])
    return powershell(script)


POWERSHELL_EXE = r"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe"


def in_session(name: str, script: str, seconds: int = 300) -> int:
    """Runs [script] in PowerShell in the Windows host's logged-on session (the scheduled task `veyq-app`, Veyq.Drive's
    launch), prints what it wrote and returns its exit code. Package deployment needs that session: from ssh's
    session 0, Add-AppxPackage fails with 0x80070005 (the AppX log: "Fehler beim Initialisieren der PLM", measured
    2026-11-13). The script goes as a file, so its length is not the command line's. On a `local` host the script runs
    here, in this process's session, which says itself first: session 0 is the trap above, named before it bites."""
    if LOCAL:
        return powershell("\n".join([
            "$session = (Get-Process -Id $PID).SessionId",
            "Write-Output ('veyq: this host runs in session ' + $session)",
            "if ($session -eq 0) { Write-Output 'veyq: session 0 cannot deploy a package (0x80070005): run this in a "
            "logged-on session'; exit 1 }",
            script,
        ]))
    ps1, out, done = (REMOTE_RUN / f"{name}{ext}" for ext in (".ps1", ".out", ".done"))
    local = SMOKE_OUT.parent / ps1.name
    local.parent.mkdir(parents=True, exist_ok=True)
    local.write_text("$ProgressPreference = 'SilentlyContinue'\n" + script + "\n", encoding="utf-8-sig")
    powershell(f"New-Item -ItemType Directory -Force -Path {ps_quote(str(REMOTE_RUN))} | Out-Null; Remove-Item -Force "
               f"-ErrorAction SilentlyContinue -Path {', '.join(ps_quote(str(p)) for p in (out, done))}", quiet=True)
    copy_to([local], REMOTE_RUN)
    local.unlink()
    command = (f"try {{ & {ps_quote(str(ps1))} *> {ps_quote(str(out))}; $c = $LASTEXITCODE }} "
               f"catch {{ $_ | Out-File -Append -FilePath {ps_quote(str(out))}; $c = 1 }} "
               f"finally {{ if ($null -eq $c) {{ $c = 0 }}; Set-Content -Path {ps_quote(str(done))} -Value $c }}")
    start = SMOKE_OUT.parent / "app.json"
    start.write_text(json.dumps({"exe": POWERSHELL_EXE, "args": ["-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                                                                 "Bypass", "-Command", command]}))
    copy_to([start], REMOTE_RUN)
    start.unlink()
    code = run_task(APP_TASK, done, seconds)
    shown = SMOKE_OUT.parent / out.name
    if fetch(str(out), shown):
        raw = shown.read_bytes()
        # Windows PowerShell's redirection writes UTF-16LE with its BOM
        text = raw.decode("utf-16") if raw[:2] == b"\xff\xfe" else raw.decode("utf-8-sig", errors="replace")
        print(text.replace("\r\n", "\n"), end="", flush=True)
        shown.unlink()
    if code != 0:
        return code
    result = SMOKE_OUT.parent / done.name
    if not fetch(str(done), result):
        sys.exit(f"veyq: {name} wrote no {done.name}")
    text = result.read_text(encoding="utf-8-sig", errors="replace").strip()
    result.unlink()
    powershell(f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {', '.join(ps_quote(str(p)) for p in (ps1, out, done))}",
               quiet=True)
    return int(text) if text.lstrip("-").isdigit() else 1


def smoke_package(signed: PureWindowsPath) -> int:
    """Installs [signed] in the Windows host's logged-on session, starts it there by its AUMID, waits until its log says
    SMOKE_SIGN_IN, then stops and uninstalls it and holds that nothing of it stays."""
    app_id = IDENTITY["appId"]
    family = REMOTE_PACKAGE / "family.txt"
    code = in_session("install", "\n".join([
        "$ErrorActionPreference = 'Stop'",
        f"Add-AppxPackage -Path {ps_quote(str(signed))}",
        f"$p = Get-AppxPackage -Name {ps_quote(app_id)}",
        "Write-Output ('veyq: installed ' + $p.PackageFullName)",
        f"Set-Content -NoNewline -Encoding ASCII -Path {ps_quote(str(family))} -Value $p.PackageFamilyName",
    ]))
    if code != 0:
        return code
    local_family = PACKAGE_OUT / "family.txt"
    if not fetch(str(family), local_family):
        sys.exit("veyq: the install named no package family")
    pfn = local_family.read_text().strip()
    local_family.unlink()
    start = SMOKE_OUT.parent / "app.json"
    start.parent.mkdir(parents=True, exist_ok=True)
    start.write_text(json.dumps({"aumid": f"{pfn}!App"}))
    started = REMOTE_RUN / "app.json.started"
    powershell(f"New-Item -ItemType Directory -Force -Path {ps_quote(str(REMOTE_RUN))} | Out-Null; "
               f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(started))}", quiet=True)
    copy_to([start], REMOTE_RUN)
    start.unlink()
    code = run_task(APP_TASK, started, 60)
    if code == 0:
        code = powershell("\n".join([
            # The packaged app keeps its data, its log too, in its package's own LocalState (AppData.cs, DataFolder),
            # never in the unpackaged build's %LOCALAPPDATA%\veyq: the log written since the activation (its .started,
            # less a margin for the log begun before it).
            f"$since = (Get-Item {ps_quote(str(started))}).LastWriteTime.AddSeconds(-10)",
            f"$path = Join-Path $env:LOCALAPPDATA {ps_quote(PACKAGE_LOG.format(family=pfn))}",
            "$deadline = (Get-Date).AddSeconds(60)",
            "while ($true) {",
            "  $log = if ((Test-Path $path) -and (Get-Item $path).LastWriteTime -gt $since) { $path } else { $null }",
            f"  if ($log -and (Select-String -Quiet -SimpleMatch {ps_quote(SMOKE_SIGN_IN)} -Path $log)) {{ break }}",
            "  if ((Get-Date) -gt $deadline) { Write-Output 'veyq: the packaged app did not reach its sign-in in 60 s'; "
            "if ($log) { Get-Content $log }; exit 1 }",
            "  Start-Sleep -Milliseconds 500",
            "}",
            "Write-Output \"--- $log\"",
            "Get-Content $log",
            f"Write-Output {ps_quote('veyq: the packaged app shows its sign-in (' + SMOKE_SIGN_IN + ')')}",
        ]))
    uninstall = in_session("uninstall", "\n".join([
        "$ErrorActionPreference = 'Stop'",
        f"Get-Process -Name {APP_NAME} -ErrorAction SilentlyContinue | Stop-Process -Force",
        "Start-Sleep -Seconds 1",
        f"Get-AppxPackage -Name {ps_quote(app_id)} | Remove-AppxPackage",
        f"$data = Join-Path $env:LOCALAPPDATA {ps_quote('Packages/' + pfn)}",
        f"if (Get-AppxPackage -Name {ps_quote(app_id)}) {{ Write-Output 'veyq: the package is still installed'; exit 1 }}",
        "if (Test-Path $data) { Write-Output \"veyq: $data is left behind\"; exit 1 }",
        f"Remove-Item -Force -ErrorAction SilentlyContinue -Path {ps_quote(str(REMOTE_RUN / 'app.json'))}",
        "Write-Output 'veyq: uninstalled; nothing of the package is left'",
    ]))
    return code or uninstall


def drop_dev_cert() -> int:
    """Removes `package --sign`'s development certificate (by its FriendlyName) from CurrentUser\\My, key and all, and
    its public half from LocalMachine\\TrustedPeople."""
    return powershell("\n".join([
        f"$mine = @(Get-ChildItem Cert:\\CurrentUser\\My | Where-Object FriendlyName -eq {ps_quote(DEV_CERT_NAME)})",
        "foreach ($c in $mine) {",
        "  Get-ChildItem Cert:\\LocalMachine\\TrustedPeople | Where-Object Thumbprint -eq $c.Thumbprint | Remove-Item",
        "  Remove-Item -DeleteKey -Path (Join-Path Cert:\\CurrentUser\\My $c.Thumbprint)",
        "  Write-Output ('veyq: removed the development certificate ' + $c.Thumbprint)",
        "}",
        "if ($mine.Count -eq 0) { Write-Output 'veyq: no development certificate on this host' }",
    ]))


def run_task(task: str, wait_for: PureWindowsPath, seconds: int) -> int:
    """Runs the scheduled [task] in the logged-on session and waits until [wait_for] exists there (at most [seconds]).
    On a `local` host Veyq.Drive runs here with the task's arguments instead, in this process's session."""
    if LOCAL:
        mode = TASKS[task]
        file = REMOTE_RUN / ("app.json" if mode == "--launch" else "steps.txt")
        command = [str(REMOTE_DRIVE / (DRIVE + ".exe")), mode, str(file)]
        print("+ " + " ".join(command), flush=True)
        try:
            subprocess.run(command, timeout=seconds)
        except subprocess.TimeoutExpired:
            pass
        deadline = time.monotonic() + seconds
        while not Path(str(wait_for)).exists():
            if time.monotonic() > deadline:
                print(f"veyq: {DRIVE} {mode} wrote no {wait_for.name} in {seconds} s", flush=True)
                return 4
            time.sleep(0.2)
        return 0
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
    code = run_task(APP_TASK, started, 30)
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
    env = {k: os.environ[k] for k in ("VEYQ_THEME", "VEYQ_LANGUAGE", "VEYQ_DEV_SCAN", "VEYQ_DEV_SETTINGS", "VEYQ_DEV_CAPTURABLE") if os.environ.get(k)} | secrets
    # a debug build's QR harness's test camera: an image on this host, copied beside the launch file
    if os.environ.get("VEYQ_DEV_CAMERA"):
        image = Path(os.environ["VEYQ_DEV_CAMERA"]).resolve()
        if not image.is_file():
            sys.exit(f"VEYQ_DEV_CAMERA names no file: {image}")
        copy_to([image], REMOTE_RUN, as_name=f"dev-camera{image.suffix}")
        env["VEYQ_DEV_CAMERA"] = str(REMOTE_RUN / f"dev-camera{image.suffix}")
    code = launch(env)
    if code != 0:
        return code
    if width and height:
        code = drive([f"size {width} {height}"])
        if code != 0:
            return code
    if os.environ.get("VEYQ_DEV_SCAN") or os.environ.get("VEYQ_DEV_SETTINGS"):
        # the harnesses sign in nowhere: no tunnel to hold
        return 0
    forward, live = live_forward(live_origin)
    print(f"veyq: the tunnel to {live} is held until this ends (Ctrl+C); the app keeps running", flush=True)
    try:
        return subprocess.run(ssh_base(forward) + ["-N"]).returncode
    except KeyboardInterrupt:
        return 0


def fetch(remote: str, local: Path) -> bool:
    """[remote] (a path on the Windows host) to [local]; False where it is not there."""
    local.parent.mkdir(parents=True, exist_ok=True)
    if LOCAL:
        if not Path(remote).is_file():
            return False
        shutil.copy2(remote, local)
        return True
    done = subprocess.run(["scp", "-q", "-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-i", str(SSH_KEY),
                           f"{SSH_TARGET}:{remote.replace(chr(92), '/')}", str(local)], capture_output=True)
    return done.returncode == 0


def drive(steps: list[str]) -> int:
    """Runs [steps] through Veyq.Drive in the logged-on session; prints the app's log, then what each step saw. A
    `shot`, `screen` or `hovershot <name>` step's PNG lands in SHOTS here."""
    shots = []
    lines = []
    for step in steps:
        verb = step.split(" ", 1)[0]
        # `screen` and `hovershot` write a PNG too: a bare name would land in the task's working directory (system32)
        if verb in ("shot", "screen", "hovershot"):
            name = PureWindowsPath(step.split(" ", 1)[1].strip()).name
            shots.append(name)
            lines.append(f"{verb} {REMOTE_RUN / 'shots' / name}")
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
    code = run_task(DRIVE_TASK, PureWindowsPath(str(steps_file) + ".done"), 300)
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
    code = run_task(APP_TASK, started, 120)
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
    k = verbs.add_parser("package", help="build the MSIX (Release, unsigned) on the Windows host; --sign signs a copy")
    k.add_argument("--sign", nargs="?", const="", metavar="PFX",
                   help="sign a copy: with PFX (password in VEYQ_MSIX_PFX_PASSWORD), else the host's development certificate")
    k.add_argument("--smoke", action="store_true", help="with --sign: install the signed copy, start it to its sign-in, uninstall")
    k.add_argument("--prebuilt", action="store_true", help="take the DLL and sources `inputs` left in this tree")
    verbs.add_parser("inputs", help="the release DLL and the generated sources with the MSIX's images, alone (CI's app-inputs)")
    verbs.add_parser("drop-dev-cert", help="remove package --sign's development certificate from the Windows host")
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
    if args.verb == "package":
        if args.smoke and args.sign is None:
            sys.exit("--smoke installs the signed copy: it goes with --sign")
        sys.exit(package(args.sign, args.smoke, args.prebuilt))
    if args.verb == "inputs":
        generate("release")
        sys.exit(0)
    if args.verb == "drop-dev-cert":
        sys.exit(drop_dev_cert())
    if args.verb == "register":
        sys.exit(register())


if __name__ == "__main__":
    main()
