#!/usr/bin/env python3
"""The docs site's older versions: which exist, building each once, and putting them beside the current docs.

ADR 0087 decision 6: the root of https://veyq.app/ is `main`'s docs; every older major's docs are the site built from
that major's **newest release tag** with `VEYQ_SITE_VERSION=<major>`, served under `/v<major>/`. "Newest" is the
order `run_web_e2e.major_entry` gives the major entry — every `v<major>.<minor>` tag, by minor as a number, a fix
release on `release/<old>.x` included — so the docs of a major are those of the release its operators run, and that
release is also the one the next major is entered from. Written here rather than imported: `run_web_e2e.py` needs
PyYAML, which site.yml's runner does not install (an import that failed inside `$(…)` would have named no version).

A version is built **once per tag** and kept on the branch `site-versions` (`v<major>/` beside `versions.json`, which
maps each major to the tag it was built from); a later deploy reuses it. Built soon after its tag, with the toolchain
of that time, it never meets a Node or a registry a year younger than its lockfile. Its install is the plain
`pnpm install --frozen-lockfile` of the tag's own `site/` lockfile, not the deprecation gate: that lockfile passed the
gate when it was `main`'s, as `run_web_e2e.prepare_predecessor` installs a predecessor's (lane L2's start tree). A version
that fails to build or fails `site_version_check.py` keeps the build it had, and the run is red.

    python3 scripts/site_versions.py older                      # `1` or `1,2`: VEYQ_SITE_OLDER of the root build
    python3 scripts/site_versions.py sync --cache site-versions # build what the cache lacks; exit 1 if one failed
    python3 scripts/site_versions.py assemble --cache site-versions --dist site/dist
    python3 scripts/site_versions.py --selftest

`--current-major <n>` (default: `VERSION`) exists for a rehearsal in a scratch clone, where a local tag stands in for
an older major's release.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil
import subprocess
import sys
import re
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
INDEX = "versions.json"
RELEASE_TAG = re.compile(r"^v(\d+)\.(\d+)$")  # run_web_e2e.RELEASE_TAG


def newest(major: int, tags: list[str]) -> str | None:
    """The newest `v<major>.<minor>` among `tags` by minor as a number; None without one."""
    found = sorted((int(m.group(2)), tag) for tag in tags if (m := RELEASE_TAG.match(tag)) and int(m.group(1)) == major)
    return found[-1][1] if found else None


def git(*args: str, cwd: pathlib.Path = ROOT) -> str:
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout


def current_major(root: pathlib.Path = ROOT) -> int:
    return int((root / "VERSION").read_text().strip())


def release_tags(root: pathlib.Path = ROOT) -> list[str]:
    return [t for t in git("tag", "--list", "v*", cwd=root).split() if RELEASE_TAG.match(t)]


def older(current: int, tags: list[str]) -> dict[int, str]:
    """Every major below `current` that has a release, mapped to its newest release tag."""
    found = {major: newest(major, tags) for major in range(1, current)}
    return {major: tag for major, tag in found.items() if tag is not None}


def plan(current: int, tags: list[str], cached: dict[str, str]) -> list[tuple[int, str]]:
    """The (major, tag) pairs the cache does not hold yet: a major never built, or one whose newest tag moved."""
    return [(major, tag) for major, tag in older(current, tags).items() if cached.get(str(major)) != tag]


def read_index(cache: pathlib.Path) -> dict[str, str]:
    path = cache / INDEX
    return json.loads(path.read_text()) if path.is_file() else {}


def build(major: int, tag: str, cache: pathlib.Path) -> bool:
    """Build `tag`'s site under `/v<major>/` into the cache, replacing what it held for `major` only once the new build
    passed site_version_check.py. False (and the cache unchanged) when anything fails."""
    with tempfile.TemporaryDirectory(prefix=f"site-v{major}-") as tmp:
        tree = pathlib.Path(tmp) / "tree"
        out = pathlib.Path(tmp) / "out"
        git("worktree", "add", "--detach", str(tree), tag)
        try:
            site = tree / "site"
            env = {**os.environ, "VEYQ_SITE_VERSION": str(major)}
            env.pop("VEYQ_SITE_OLDER", None)
            steps = (
                ["pnpm", "install", "--frozen-lockfile"],
                ["pnpm", "exec", "astro", "build", "--outDir", str(out)],
            )
            for cmd in steps:
                print(f"site_versions: v{major} from {tag}: {' '.join(cmd)}", flush=True)
                if subprocess.run(cmd, cwd=site, env=env).returncode != 0:
                    print(f"::error::site_versions: v{major} from {tag}: `{' '.join(cmd)}` failed; v{major} keeps the "
                          "build it had")
                    return False
            check = [sys.executable, str(ROOT / "scripts" / "site_version_check.py"), str(out), "--base", f"/v{major}/"]
            if subprocess.run(check).returncode != 0:
                print(f"::error::site_versions: v{major} from {tag} leaves its version (site_version_check.py); "
                      f"v{major} keeps the build it had")
                return False
            target = cache / f"v{major}"
            if target.exists():
                shutil.rmtree(target)
            shutil.copytree(out, target)
            index = read_index(cache)
            index[str(major)] = tag
            (cache / INDEX).write_text(json.dumps(dict(sorted(index.items())), indent=2) + "\n")
            print(f"site_versions: v{major} built from {tag}")
            return True
        finally:
            git("worktree", "remove", "--force", str(tree))


def sync(cache: pathlib.Path, current: int) -> int:
    cache.mkdir(parents=True, exist_ok=True)
    # An empty index on the first run, so the first deploy after this was built already writes the branch: the write
    # path (the token's rights, a ruleset on new branches) is proved then, not at the first train that needs it.
    if not (cache / INDEX).is_file():
        (cache / INDEX).write_text("{}\n")
    todo = plan(current, release_tags(), read_index(cache))
    if not todo:
        print(f"site_versions: the cache holds every older major's newest release ({read_index(cache) or 'none yet'})")
        return 0
    failed = [major for major, tag in todo if not build(major, tag, cache)]
    return 1 if failed else 0


def assemble(cache: pathlib.Path, dist: pathlib.Path, current: int) -> int:
    """Copy every older major's cached build into `dist/v<major>/`. Red when one the root build names is missing: its
    header would link a version that is not there."""
    wanted = older(current, release_tags())
    index = read_index(cache)
    missing = [major for major in wanted if str(major) not in index or not (cache / f"v{major}").is_dir()]
    if missing:
        print(f"::error::site_versions: no build of v{', v'.join(map(str, missing))} in {cache}: the sync that builds "
              "it failed (its log says why)")
        return 1
    for major in wanted:
        shutil.copytree(cache / f"v{major}", dist / f"v{major}")
        print(f"site_versions: /v{major}/ is {index[str(major)]}'s docs")
    if not wanted:
        print("site_versions: no older major has a release; the site is the root build alone")
    return 0


def selftest() -> int:
    """Bites on the pure part — which majors are older, which tag each is, what a cache still lacks."""
    tags = ["v0.9", "v1.2", "v1.10", "v1.9", "v2.0", "v2.3", "v3.1", "snapshot-1.4", "v1.x"]
    cases = {
        "one major: nothing older": (older(1, tags), {}),
        "major 0 is never a version": (older(2, ["v0.9"]), {}),
        "newest by version order, not by text": (older(2, tags), {1: "v1.10"}),
        "every older major with a release": (older(4, tags), {1: "v1.10", 2: "v2.3", 3: "v3.1"}),
        "a major without a release is skipped": (older(4, ["v1.2", "v3.1"]), {1: "v1.2", 3: "v3.1"}),
        "empty cache builds all": (plan(3, tags, {}), [(1, "v1.10"), (2, "v2.3")]),
        "cache current builds nothing": (plan(3, tags, {"1": "v1.10", "2": "v2.3"}), []),
        "a fix release moves the tag": (plan(3, tags, {"1": "v1.9", "2": "v2.3"}), [(1, "v1.10")]),
    }
    failed = 0
    for label, (got, expected) in cases.items():
        ok = got == expected
        print(f"{'ok  ' if ok else 'FAIL'} {label}: {got}")
        failed += not ok
    print(f"site_versions selftest: {len(cases) - failed}/{len(cases)} green")
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", nargs="?", choices=["older", "sync", "assemble"])
    parser.add_argument("--cache", type=pathlib.Path, help="a checkout of the site-versions branch (may be empty)")
    parser.add_argument("--dist", type=pathlib.Path, help="the root build to put the versions into")
    parser.add_argument("--current-major", type=int, help="the current major (default: VERSION)")
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    current = args.current_major or current_major()
    if args.command == "older":
        print(",".join(map(str, older(current, release_tags()))))
        return 0
    if args.cache is None:
        parser.error(f"{args.command} needs --cache")
    if args.command == "sync":
        return sync(args.cache.resolve(), current)
    if args.command == "assemble":
        if args.dist is None or not args.dist.is_dir():
            parser.error("assemble needs --dist, the root build")
        return assemble(args.cache.resolve(), args.dist.resolve(), current)
    parser.error("name a command, or --selftest")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
