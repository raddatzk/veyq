#!/usr/bin/env python3
"""`pnpm install --frozen-lockfile`, refused when a locked package is deprecated — the pnpm half of the root CLAUDE.md
dependency rule.

**What it asserts.** It runs `pnpm install --frozen-lockfile` from the repository root — or, with `--package-dir <dir>`,
from a package directory outside the root workspace that carries its own `pnpm-lock.yaml` (`site/`, ADR 0087) —
passing any other argument through to pnpm, echoes pnpm's output as it comes, and exits non-zero when pnpm fails **or**
any locked package is deprecated, naming every one it found. Three sources, because a frozen install resolves nothing
and so prints no deprecation warning of its own (measured 2026-11-03 with pnpm 12.16.1: a scratch package locking
`request@2.88.2` installs frozen in silence; only the resolving `pnpm install` printed `WARN deprecated request@2.88.2`):
  - **the lockfile's marks, offline.** pnpm copies a package's `deprecated` message into its `pnpm-lock.yaml` entry when
    it resolves it, and the lockfile holds every platform's optional bindings (esbuild's, rolldown's, lightningcss's) —
    so every entry carrying the mark is refused, whatever platform runs this;
  - **the registry, for what was deprecated after the lock was written.** Every locked `name@version` — every
    document of the lockfile, pnpm's own pinned executables included — is asked of the registry
    (`pnpm view <name>@<version> deprecated --json`). A query the registry does not answer is red, never read as
    "not deprecated";
  - **pnpm's own warnings** (`WARN deprecated <name>@<version>`, `N deprecated subdependencies found: …`), for a run
    that does resolve — a caller passing `--no-frozen-lockfile`.
Two things keep a green from meaning "nothing was looked at": `--frozen-lockfile` is always passed (an install that
does not match the lockfile fails instead of re-resolving it), and pnpm's closing line (`Done in …`) must be in the
captured output — the positive control that pnpm's output reached this process at all; without it the run is red.
`--loglevel=info` (pnpm's default) is appended **last**, so neither an `.npmrc` nor an argument passed through can
raise the level past `warn` and hide a warning this reads.

**One CI job per lockfile asks the registry, the others pass `--skip-registry-check`** (inbox #312). The registry half
is one query per locked package (~630 for the root lockfile, ~550 for `site/`'s; pnpm's `view` is a native binary, so
they run 16 at a time in seconds) whose answer depends only on the lockfile, and every gated job that installs the same
lockfile does so at the same commit — so asking it once per commit is the whole check. A second lockfile (`site/`'s) is
a different question, so its own install asks it once. The flag is an opt-out on purpose: a run without it (every local
run, and a new CI caller that forgets it) asks the registry, so forgetting costs time, never coverage. The asking job is
the one whose workflow fires on every branch and every lockfile change the others fire on and whose job every image
build `needs:` — `validate_pnpm_installs_gated.py` pins all three, and that exactly one job asks. The offline half (the
lockfile's own `deprecated` marks) still runs everywhere.

**The defect that bought it** (inbox #213 → #232, 2026-10-01, under npm). `@vue/test-utils` pulled `js-beautify`, which
pulled `glob@10.5.0`, deprecated by its maintainer; `npm ci` printed the warning on every install and nothing failed,
so the rule *"never keep a deprecated dependency"* was held by whoever happened to read an install log. It was found by
a review, not by the build. The lockfile half was bought by #306: the gating jobs run on x64, so the arm64 image's own
bindings had never been looked at. Rewritten for pnpm when the repository moved to it (2026-11-03): bitten with a
scratch package locking `request@2.88.2` (red, naming it and its deprecated `har-validator@5.1.5` and `uuid@3.4.0`,
offline and from the registry) against one locking `is-number@7.0.0` (green).

**Its limits — what a green run does NOT prove.** Only what the registry marks `deprecated`: a package that is
archived, abandoned or unmaintained without that mark passes (the root CLAUDE.md rule is wider; `src/web/CLAUDE.md`
§ jsqr is the standing example — neither archived nor deprecated, and stale). Only the pnpm tree: Gradle has no such
mark. Not inside the images: the Dockerfiles' own `pnpm install --frozen-lockfile` does not call it, and is covered
because it installs the lockfile this run scanned, in a build job whose publication `needs:` this one
(`validate_pnpm_installs_gated.py` holds the premises). Only at install time: a package deprecated after the last run is
caught by the next one, not before. And only where it is called — every pnpm install in `.github/workflows/` goes
through it, which `validate_pnpm_installs_gated.py` holds. It needs the network and a full install, so it is not a
buildless `validate_*.py`; it replaces the install in the CI jobs instead. Runnable locally the same way:

    python3 scripts/pnpm_install_no_deprecated.py            # a full install, as CI does
    python3 scripts/pnpm_install_no_deprecated.py --ignore-scripts
    python3 scripts/pnpm_install_no_deprecated.py --skip-registry-check   # the non-designated CI jobs only
    python3 scripts/pnpm_install_no_deprecated.py --package-dir site       # the docs site, its own lockfile
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import pathlib
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from typing import Callable

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOCKFILE = "pnpm-lock.yaml"
SKIP_REGISTRY = "--skip-registry-check"  # this script's own flag, never passed on to pnpm
PACKAGE_DIR = "--package-dir"  # this script's own option (`--package-dir <dir>`), never passed on to pnpm

WARNED = re.compile(r"^\W*WARN\W+deprecated (?P<pkg>\S+?):?(?:\s|$)", re.IGNORECASE)
WARNED_SUBDEPS = re.compile(r"^\W*WARN\W+\d+ deprecated subdependenc(?:y|ies) found: (?P<pkgs>.+)$", re.IGNORECASE)
SUMMARY = re.compile(r"^Done in \S+")

# pnpm-lock.yaml is machine-written YAML; read line by line so the gate needs no YAML library on the CI runner.
SECTION = re.compile(r"^(?P<name>[A-Za-z]\w*):")
ENTRY = re.compile(r"^  (?! )(?P<key>.+?):(?:\s+\{\})?\s*$")
DEPRECATED_FIELD = re.compile(r"^    deprecated:\s*\S")


def scan(lines: list[str]) -> tuple[list[str], bool]:
    """The deprecated packages pnpm warned about, and whether its closing line was seen."""
    found: list[str] = []
    for line in lines:
        m = WARNED.match(line)
        if m:
            found.append(m.group("pkg"))
        m = WARNED_SUBDEPS.match(line)
        if m:
            found.extend(p.strip() for p in m.group("pkgs").split(",") if p.strip())
    return found, any(SUMMARY.match(line) for line in lines)


def locked(text: str) -> dict[str, bool]:
    """Every registry package the lockfile pins, as `name@version`, and whether its entry carries a `deprecated` mark.
    Every document of the file is read: pnpm writes its own pinned executables into a first one."""
    out: dict[str, bool] = {}
    section, current = "", ""
    for line in text.splitlines():
        if line.startswith("---"):
            section, current = "", ""
            continue
        m = SECTION.match(line)
        if m:
            section, current = m.group("name"), ""
            continue
        if section != "packages":
            continue
        m = ENTRY.match(line)
        if m:
            current = m.group("key").strip("'\"")
            out.setdefault(current, False)
        elif current and DEPRECATED_FIELD.match(line):
            out[current] = True
    return out


def registry_spec(key: str) -> str:
    """`name@version` as the registry knows it, or "" for an entry that is not a registry package (a tarball URL, a git
    or a local path) — pnpm keys those by their location, never by a bare version."""
    name, _, version = key.rpartition("@")
    if not name or not re.match(r"^\d+\.\d+\.\d+", version):
        return ""
    return f"{name}@{version}"


def pnpm_view(spec: str, cwd: pathlib.Path = ROOT) -> tuple[int, str]:
    """The registry's deprecation message for one `name@version` (empty when none), and pnpm's exit code — asked from
    the installed package's directory, so its `.npmrc` names the registry."""
    r = subprocess.run(["pnpm", "view", spec, "deprecated", "--json"], cwd=cwd, capture_output=True, text=True,
                       errors="replace")
    return r.returncode, r.stdout.strip()


def registry_deprecated(specs: list[str], view: Callable[[str], tuple[int, str]] = pnpm_view) -> tuple[list[str], list[str]]:
    """Ask the registry about each spec: (the deprecated ones, the ones it could not answer for)."""
    with ThreadPoolExecutor(max_workers=16) as pool:
        answers = list(pool.map(view, specs))
    marked = [s for s, (code, out) in zip(specs, answers) if code == 0 and out not in ("", '""')]
    failed = [s for s, (code, _) in zip(specs, answers) if code != 0]
    return marked, failed


def check_lockfile(text: str, view: Callable[[str], tuple[int, str]] = pnpm_view,
                   ask_registry: bool = True) -> tuple[list[str], list[str], int]:
    """(deprecated specs, specs the registry could not answer for, how many registry specs the lockfile pins)."""
    entries = locked(text)
    marked_offline = {registry_spec(k) or k for k, deprecated in entries.items() if deprecated}
    specs = sorted({s for s in (registry_spec(k) for k in entries) if s})
    marked, failed = registry_deprecated(specs, view) if ask_registry else ([], [])
    return sorted(marked_offline | set(marked)), failed, len(specs)


def package_dir(argv: list[str]) -> tuple[pathlib.Path | None, list[str], str]:
    """The directory to install (the repo root unless `--package-dir <dir>` names one), the arguments left for pnpm,
    and why the request is refused (empty when it is not). A named directory must be inside the repository and carry
    its own `package.json` and `pnpm-lock.yaml`: a root-workspace member has no lockfile of its own, and an install
    there would install the root's — so the directory would be named here and not be what is scanned."""
    if any(a.startswith(PACKAGE_DIR + "=") for a in argv):
        return None, argv, f"write {PACKAGE_DIR} and its directory as two arguments"
    if PACKAGE_DIR not in argv:
        return ROOT, argv, ""
    at = argv.index(PACKAGE_DIR)
    if at + 1 >= len(argv) or argv[at + 1].startswith("-"):
        return None, argv, f"{PACKAGE_DIR} needs a directory"
    rest = argv[:at] + argv[at + 2:]
    if PACKAGE_DIR in rest:
        return None, argv, f"{PACKAGE_DIR} given twice"
    cwd = (ROOT / argv[at + 1]).resolve()
    if cwd != ROOT and ROOT not in cwd.parents:
        return None, argv, f"{argv[at + 1]} is outside the repository"
    for name in ("package.json", LOCKFILE):
        if not (cwd / name).is_file():
            return None, argv, f"{argv[at + 1]} has no {name} of its own"
    return cwd, rest, ""


def main(argv: list[str]) -> int:
    ask_registry = SKIP_REGISTRY not in argv
    argv = [a for a in argv if a != SKIP_REGISTRY]
    cwd, argv, refused = package_dir(argv)
    if cwd is None:
        print(f"REFUSED: {refused}", file=sys.stderr)
        return 2
    if any(a in ("--no-frozen-lockfile", "--fix-lockfile") or a.startswith("--frozen-lockfile=") for a in argv):
        print("REFUSED: this gate installs the lockfile as it is — it does not re-resolve it", file=sys.stderr)
        return 2
    cmd = ["pnpm", "install", "--frozen-lockfile", *argv, "--loglevel=info"]
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            errors="replace")
    assert proc.stdout is not None
    lines: list[str] = []
    for line in proc.stdout:
        sys.stdout.write(line)
        lines.append(line.rstrip("\n"))
    code = proc.wait()
    if code != 0:
        print(f"pnpm install failed (exit {code}) in {cwd}", file=sys.stderr)
        return code
    warned, summary = scan(lines)
    in_lock, unanswered, pinned = check_lockfile((cwd / LOCKFILE).read_text(),
                                                 view=lambda spec: pnpm_view(spec, cwd), ask_registry=ask_registry)
    deprecated = sorted(set(warned) | set(in_lock))
    if unanswered:
        print(f"\nREFUSED: the registry gave no answer for {len(unanswered)} locked package(s), so their deprecation "
              "is unknown:", file=sys.stderr)
        for pkg in unanswered:
            print(f"  - {pkg}", file=sys.stderr)
        return 1
    if deprecated:
        print(f"\nREFUSED: the lockfile in {cwd} pins {len(deprecated)} deprecated package(s) — replace them now "
              "(root CLAUDE.md: no deprecated dependencies):", file=sys.stderr)
        for pkg in deprecated:
            print(f"  - {pkg}", file=sys.stderr)
        return 1
    if not summary:
        print("\nREFUSED: pnpm printed no closing `Done in …` line, so this process cannot tell 'no deprecation "
              "warning' from 'no output' — was the install silenced?", file=sys.stderr)
        return 1
    if not ask_registry:
        print(f"pnpm install: no deprecated package marked in the lockfile; its {pinned} locked package(s) were not "
              f"asked of the registry ({SKIP_REGISTRY}: the designated job asks for this commit).")
        return 0
    print(f"pnpm install: no deprecated package marked in the lockfile, nor among its {pinned} locked package(s) "
          "asked of the registry.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
