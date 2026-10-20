#!/usr/bin/env python3
"""`npm ci`, refused when npm reports a deprecated package — the npm half of the root CLAUDE.md dependency rule.

**What it asserts.** It runs `npm ci` from the repository root — or, with `--package-dir <dir>`, from a package
directory outside the root workspace that carries its own `package-lock.json` (`site/`, ADR 0087) — passing any
other argument through to npm, echoes
npm's output as it comes, and exits non-zero when npm fails **or** when any line of its output is npm's
deprecation warning (`npm warn deprecated <name>@<version>: …`, either case of `warn`), naming every package it
saw. The registry marks a package deprecated in its packument and `npm ci` prints that mark for every installed
`name@version` that carries it, so the whole resolved tree is covered, transitive dependencies included — which
is where the one defect so far sat. Two things keep a green from meaning "nothing was looked at":
  - `--loglevel=notice` (npm's default) is appended **last**, so neither an `.npmrc` nor an argument passed
    through can raise the level past `warn` and hide the line this reads;
  - npm's install summary (`added N packages` / `up to date`) must be in the captured output — the positive
    control that npm's output reached this process at all; without it the run is red, not green.

npm warns only for what it **installs**, and it installs a platform-restricted optional package only on its own
platform — so the lockfile half covers the rest (inbox #306). Every `package-lock.json` entry is refused when it
carries the `deprecated` mark npm copies from the manifest at lock time (offline, every platform), and every entry
this install skipped — no directory at its path: another platform's esbuild, rolldown and lightningcss bindings —
is asked of the registry directly (`npm view <name>@<version> deprecated`). A query the registry does not answer
is red, never read as "not deprecated".

**One CI job per lockfile asks the registry, the others pass `--skip-registry-check`** (inbox #312). The registry
half is ~54 queries (~16 s, network-dependent) whose answer depends only on the lockfile, and every gated job that
installs the same lockfile does so at the same commit — so asking it once per commit is the whole check, four times
is three repeats. A second lockfile (`site/`'s) is a different question, so its own install asks it once.
The flag is an opt-out on purpose: a run without it (every local run, and a new CI caller that forgets it) asks
the registry, so forgetting costs time, never coverage. The asking job is the one whose workflow fires on every
branch and every lockfile change the others fire on and whose job every image build `needs:` (the arm64 image is
where another platform's bindings are actually installed) — `validate_npm_installs_gated.py` pins all three, and
that exactly one job asks. The offline half (the lockfile's own `deprecated` marks) still runs everywhere.

**The defect that bought it** (inbox #213 → #232, 2026-10-01). `@vue/test-utils` pulled `js-beautify`, which
pulled `glob@10.5.0`, deprecated by its maintainer; `npm ci` printed the warning on every install and nothing
failed, so the rule *"never keep a deprecated dependency"* was held by whoever happened to read an install log.
It was found by a review, not by the build. Bitten against that lockfile (the tree before `f21c50b`): red,
naming `glob@10.5.0`. The lockfile half was bought by #306: the gating jobs run on x64, so the arm64 image's own
bindings had never been looked at.

**Its limits — what a green run does NOT prove.** Only what the registry marks `deprecated`: a package that is
archived, abandoned or unmaintained without that mark passes (the root CLAUDE.md rule is wider; `src/web/CLAUDE.md`
§ jsqr is the standing example — neither archived nor deprecated, and stale). Only the npm tree: Gradle has no
such mark. Not inside the images: the Dockerfiles' own `npm ci` does not call it, and is covered because it
installs the lockfile this run scanned, in a build job that `needs:` this one (`validate_npm_installs_gated.py`
holds the premises) — another platform's bindings included, through the lockfile half. That half sees a skipped
package only by its lockfile path, so one the lockfile does not pin is out of its reach (an image installs the
lockfile, so there is none today). Only at install time: a package deprecated after the last run is caught by the next one, not
before. And only where it is called — every npm install in `.github/workflows/` goes through it, which
`validate_npm_installs_gated.py` holds. It needs the network and a full install, so it is not a buildless
`validate_*.py`; it replaces `npm ci` in the CI jobs instead. Runnable locally the same way:

    python3 scripts/npm_ci_no_deprecated.py            # a full install, as CI does
    python3 scripts/npm_ci_no_deprecated.py --ignore-scripts
    python3 scripts/npm_ci_no_deprecated.py --skip-registry-check   # the non-designated CI jobs only
    python3 scripts/npm_ci_no_deprecated.py --package-dir site       # the docs site, its own lockfile
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import json
import pathlib
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from typing import Callable

ROOT = pathlib.Path(__file__).resolve().parent.parent
SKIP_REGISTRY = "--skip-registry-check"  # this script's own flag, never passed on to npm
PACKAGE_DIR = "--package-dir"  # this script's own option (`--package-dir <dir>`), never passed on to npm

DEPRECATED = re.compile(r"^npm (?:warn|WARN) deprecated (?P<pkg>\S+?):?(?:\s|$)")
SUMMARY = re.compile(r"^(?:added|removed|changed) \d+ packages?|^up to date")


def scan(lines: list[str]) -> tuple[list[str], bool]:
    """The deprecated packages npm named, and whether its install summary was seen."""
    deprecated = [m.group("pkg") for m in (DEPRECATED.match(line) for line in lines) if m]
    return deprecated, any(SUMMARY.match(line) for line in lines)


def locked(lock: dict) -> dict[str, dict]:
    """Every registry package the lockfile pins, by install path — the root and the workspace links left out."""
    return {path: e for path, e in (lock.get("packages") or {}).items() if "node_modules/" in path and not e.get("link")}


def spec_of(path: str, entry: dict) -> str:
    """`name@version` as the registry knows it — an aliased entry carries its real name in `name`."""
    return f"{entry.get('name') or path.rsplit('node_modules/', 1)[1]}@{entry['version']}"


def locked_deprecated(lock: dict) -> list[str]:
    """Offline, every platform: npm copies a manifest's `deprecated` into the lockfile entry when it writes it."""
    return sorted({spec_of(path, e) for path, e in locked(lock).items() if e.get("deprecated")})


def not_installed(lock: dict, root: pathlib.Path) -> list[str]:
    """What this install skipped — another platform's optional bindings — so npm printed no warning for it."""
    return sorted({spec_of(path, e) for path, e in locked(lock).items() if not (root / path).is_dir()})


def npm_view(spec: str, cwd: pathlib.Path = ROOT) -> tuple[int, str]:
    """The registry's deprecation message for one `name@version` (empty when none), and npm's exit code — asked
    from the installed package's directory, so its `.npmrc` names the registry."""
    r = subprocess.run(["npm", "view", spec, "deprecated", "--json"], cwd=cwd, capture_output=True, text=True,
                       errors="replace")
    return r.returncode, r.stdout.strip()


def registry_deprecated(specs: list[str], view: Callable[[str], tuple[int, str]] = npm_view) -> tuple[list[str], list[str]]:
    """Ask the registry about each spec: (the deprecated ones, the ones it could not answer for)."""
    with ThreadPoolExecutor(max_workers=8) as pool:
        answers = list(pool.map(view, specs))
    marked = [s for s, (code, out) in zip(specs, answers) if code == 0 and out not in ("", '""')]
    failed = [s for s, (code, _) in zip(specs, answers) if code != 0]
    return marked, failed


def check_lockfile(lock: dict, root: pathlib.Path, view: Callable[[str], tuple[int, str]] = npm_view,
                   ask_registry: bool = True) -> tuple[list[str], list[str], int]:
    """The lockfile half (inbox #306): (deprecated specs, specs the registry could not answer for, how many specs
    this install skipped). npm warns only for what it installs, so every entry it skipped is asked for directly —
    unless `ask_registry` is off, which leaves that to the one CI job that asks (inbox #312)."""
    skipped = not_installed(lock, root)
    marked, failed = registry_deprecated(skipped, view) if ask_registry else ([], [])
    return sorted(set(locked_deprecated(lock)) | set(marked)), failed, len(skipped)


def package_dir(argv: list[str]) -> tuple[pathlib.Path | None, list[str], str]:
    """The directory to install (the repo root unless `--package-dir <dir>` names one), the arguments left for npm,
    and why the request is refused (empty when it is not). A named directory must be inside the repository and
    carry its own `package.json` and `package-lock.json`: a root-workspace member has no lockfile of its own, and
    `npm ci` there would install the root's — so the directory would be named here and not be what is scanned."""
    if any(a.startswith(PACKAGE_DIR + "=") for a in argv):
        # npm would take it as a config of its own and install the repo root — not the directory it names.
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
    for name in ("package.json", "package-lock.json"):
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
    cmd = ["npm", "ci", *argv, "--loglevel=notice"]
    proc = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            errors="replace")
    assert proc.stdout is not None
    lines: list[str] = []
    for line in proc.stdout:
        sys.stdout.write(line)
        lines.append(line.rstrip("\n"))
    code = proc.wait()
    if code != 0:
        print(f"npm ci failed (exit {code}) in {cwd}", file=sys.stderr)
        return code
    deprecated, summary = scan(lines)
    in_lock, unanswered, skipped = check_lockfile(json.loads((cwd / "package-lock.json").read_text()), cwd,
                                                  view=lambda spec: npm_view(spec, cwd), ask_registry=ask_registry)
    deprecated = sorted(set(deprecated) | set(in_lock))
    if unanswered:
        print(f"\nREFUSED: the registry gave no answer for {len(unanswered)} locked package(s) this install skipped, "
              "so their deprecation is unknown:", file=sys.stderr)
        for pkg in unanswered:
            print(f"  - {pkg}", file=sys.stderr)
        return 1
    if deprecated:
        print(f"\nREFUSED: npm ci installed {len(deprecated)} deprecated package(s) — replace them now "
              "(root CLAUDE.md: no deprecated dependencies):", file=sys.stderr)
        for pkg in deprecated:
            print(f"  - {pkg}", file=sys.stderr)
        return 1
    if not summary:
        print("\nREFUSED: npm ci printed no install summary, so this process cannot tell 'no deprecation "
              "warning' from 'no output' — was the install silenced?", file=sys.stderr)
        return 1
    if not ask_registry:
        print(f"npm ci: no deprecated package in the installed tree, nor marked in the lockfile; the {skipped} locked "
              f"package(s) this platform skipped were not asked of the registry ({SKIP_REGISTRY}: the designated "
              "job asks for this commit).")
        return 0
    print(f"npm ci: no deprecated package in the installed tree, nor among the {skipped} locked package(s) this "
          "platform skipped.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
