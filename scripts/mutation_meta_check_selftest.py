#!/usr/bin/env python3
"""Pin the mutation meta-checks' documented total-vs-per-glob asymmetry (inbox #109, ADR 0045 §4).

Two sibling guards, deliberately different:
  - `mutation_meta_check.py` (crypto/web): asserts the report's **TOTAL** mutant count > 0, and only when the
    PR diff intersects the mutate scope — StrykerJS OSS has no diff scoping, so a total-0 on a touched scope is
    a broken run, not a correctly-empty one.
  - `mutation_meta_check_backend.py`: asserts **each** `targetClasses` glob is individually > 0, on every run —
    PIT's `failWhenNoMutations` already covers the global-empty case; the delta is one stale glob whose
    non-empty siblings keep the build green.

That asymmetry lives only in the two docstrings: make the backend check total-count, or the web check per-glob,
and the prose goes silently false (the repo's recurring "Trap 5"). This selftest makes it executable — the same
"one member empty, a sibling full" fixture must be **rejected** by the backend check and **accepted** by the web
check (total still > 0).

Why it is not a `validate_*.py`: the web check shells out to `git diff`, but the trust-chain glob's harness
(`validate_guard_gaps.py`) copies the tree **without** `.git` (none of its targets reads history) — so a
git-dependent check cannot live there (cr-0180). This fabricates its own Stryker/PIT reports **and** a throwaway
git repo, so it needs no real mutation run; it is invoked from both jobs of `mutation.yml` (`crypto`, `backend`) beside the
real checks. Runnable locally:

    python3 scripts/mutation_meta_check_selftest.py
"""
from __future__ import annotations  # `dict | None` below — the repo's guards run on Python 3.9

import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
# The two projects' repository-relative directories, as the check itself reads them (and the backend's, for PIT's).
WEB = repo_layout.rel(repo_layout.WEB)
CRYPTO = repo_layout.rel(repo_layout.CRYPTO)
BACKEND = repo_layout.rel(repo_layout.BACKEND)


def _copy_check(tree: pathlib.Path, name: str) -> None:
    """The check and the layout module it reads, so the copy resolves every project inside `tree`."""
    (tree / "scripts").mkdir(parents=True, exist_ok=True)
    for script in (name, "repo_layout.py"):
        shutil.copy(SCRIPTS / script, tree / "scripts" / script)


def _run_check(tree: pathlib.Path, name: str, *args: str) -> int:
    """Run the COPIED meta-check — its `ROOT = __file__.parent.parent` binds to the temp tree, so it reads the
    fabricated manifest/report there (the same trick validate_guard_gaps uses)."""
    return subprocess.run(
        [sys.executable, str(tree / "scripts" / name), *args],
        cwd=tree, capture_output=True, text=True,
    ).returncode


def _pit_report(mutations: list[tuple[str, bool]]) -> str:
    """`mutations`: (mutatedClass, detected) pairs. The `detected` attribute is PIT's own kill flag and the
    survivor-budget check keys on it, so the fixture must carry it — an attribute-less mutation would read as
    undetected and quietly turn every legacy case into a budget case."""
    muts = "".join(f'<mutation detected="{str(d).lower()}" status="{"KILLED" if d else "SURVIVED"}">'
                   f"<mutatedClass>{c}</mutatedClass></mutation>" for c, d in mutations)
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<mutations>{muts}</mutations>\n'


def backend_case(alpha: tuple[int, int] = (1, 0), beta: tuple[int, int] = (0, 0)) -> int:
    """Two scope globs (Alpha, Beta), each given as (mutations, undetected). Alpha defaults to one killed
    mutant — the healthy sibling — so `beta=(0, 0)` is the 'one stale glob among non-empty siblings' the
    per-glob non-empty check must catch, and a loaded `beta` exercises the survivor budget."""
    with tempfile.TemporaryDirectory() as d:
        tree = pathlib.Path(d)
        _copy_check(tree, "mutation_meta_check_backend.py")
        (tree / BACKEND / "core").mkdir(parents=True)
        (tree / BACKEND / "core" / "pitest.scope.json").write_text(
            json.dumps({"targetClasses": ["me.raddatz.veyq.app.Alpha", "me.raddatz.veyq.app.Beta"]}))
        rep = tree / BACKEND / "core" / "build" / "reports" / "pitest"
        rep.mkdir(parents=True)
        muts: list[tuple[str, bool]] = []
        for cls, (total, undetected) in (("me.raddatz.veyq.app.Alpha", alpha), ("me.raddatz.veyq.app.Beta", beta)):
            muts += [(cls, i >= undetected) for i in range(total)]
        (rep / "mutations.xml").write_text(_pit_report(muts))
        return _run_check(tree, "mutation_meta_check_backend.py")


def _git(tree: pathlib.Path, *args: str) -> None:
    # `-c commit.gpgsign=false`: the fabricated repo inherits the DEVELOPER's global git config, and a machine
    # that signs commits (ssh-format signing via a 1Password/gpg agent) fails the commit outright — "failed to
    # fill whole buffer", exit 128 — whenever that agent is locked. veyq's own checkout masks it with a local
    # `commit.gpgsign=false`, and CI has no signing config at all, so this only ever bites on a dev machine,
    # intermittently, in a check whose subject has nothing to do with git. A throwaway fixture must not depend
    # on the host's signing setup.
    subprocess.run(["git", "-c", "commit.gpgsign=false", *args],
                   cwd=tree, check=True, capture_output=True, text=True)


def web_case(mutate: list[str], diff_file: str, total_mutants: int,
             project: str = WEB, other: dict | None = None, missing: str | None = None) -> int:
    """`mutate`: project-relative scope entries. `diff_file`: repo-relative file changed+committed (so
    `base...HEAD` carries it). `total_mutants`: mutants placed on the first scoped file in the report.

    `project` picks which of the two real projects the fixture stands in for (`web` or `packages/crypto`) —
    the check is per project, so the crypto arm must be exercised on its own and not inferred from web's.
    `other` optionally fabricates the SIBLING project too, as {"project", "mutate", "total_mutants"} — that
    is how the union-vs-per-project distinction is pinned: a healthy sibling must not excuse an empty one. Without
    `other` the sibling is fabricated anyway, its scope away from the diff — the check fails on a project whose
    manifest is absent — and `missing` names a project whose manifest is then left out."""
    with tempfile.TemporaryDirectory() as d:
        tree = pathlib.Path(d)
        _copy_check(tree, "mutation_meta_check.py")
        projects = [{"project": project, "mutate": mutate, "total_mutants": total_mutants}]
        sibling = CRYPTO if project == WEB else WEB
        projects.append(other or {"project": sibling, "mutate": ["src/untouched.ts"], "total_mutants": 0})
        scoped_files: set[str] = set()
        for spec in projects:
            proj = tree / spec["project"]
            proj.mkdir(parents=True)
            if spec["project"] != missing:
                (proj / "stryker.mutate.json").write_text(json.dumps({"mutate": spec["mutate"]}))
            scoped_files |= {f"{spec['project']}/{m}" for m in spec["mutate"]}
        # Base tree: every scoped file + the diff target, so a diff can hit either in- or out-of-scope.
        for rel in scoped_files | {diff_file}:
            p = tree / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text("// base\n")
        _git(tree, "init", "-q")
        _git(tree, "config", "user.email", "t@t")
        _git(tree, "config", "user.name", "t")
        _git(tree, "add", "-A")
        _git(tree, "commit", "-qm", "base")
        base = subprocess.run(["git", "rev-parse", "HEAD"], cwd=tree,
                              capture_output=True, text=True).stdout.strip()
        # The change as a new commit, so `git diff base...HEAD` carries it.
        (tree / diff_file).write_text("// changed\n")
        _git(tree, "add", "-A")
        _git(tree, "commit", "-qm", "change")
        for spec in projects:
            rep = tree / spec["project"] / "reports" / "mutation"
            rep.mkdir(parents=True)
            n = spec["total_mutants"]
            files = {f"{spec['project']}/{spec['mutate'][0]}": {"mutants": [{}] * n}} if n else {}
            (rep / "mutation.json").write_text(json.dumps({"files": files}))
        return _run_check(tree, "mutation_meta_check.py", "--base", base)


V1 = "src/crypto/v1.ts"
V2 = "src/crypto/v2.ts"

results: list[tuple[str, bool]] = []


def expect(name: str, actual: int, wanted: int) -> None:
    ok = actual == wanted
    results.append((name, ok))
    print(f"  {'OK  ' if ok else 'FAIL'} {name} (exit {actual}, expected {wanted})")


def main() -> int:
    print("mutation meta-check selftest — pinning the total-vs-per-glob asymmetry (ADR 0045 §4):")

    # Backend = per-glob: a 0-mutation glob among non-empty siblings must fail; all-non-empty passes.
    expect("backend: a 0-mutation glob among non-empty siblings FAILS (per-glob)",
           backend_case(beta=(0, 0)), 1)
    expect("backend: every glob non-empty PASSES", backend_case(beta=(2, 0)), 0)

    # The survivor budget (inbox #116), replayed on the real incident's shape: 56/58 = 96.6 % is ABOVE the
    # build's aggregate threshold of 95, yet both survivors sit in one glob (Beta = TokenBucket at 23/25).
    # The aggregate stays green; the per-glob budget is what must red.
    expect("backend: 2 survivors in ONE glob FAILS though the total is 56/58 = 96.6 % (> 95)",
           backend_case(alpha=(33, 0), beta=(25, 2)), 1)
    expect("backend: exactly 1 survivor PASSES — the budget is the threshold's headroom, per class",
           backend_case(alpha=(33, 0), beta=(25, 1)), 0)
    expect("backend: a 1-mutation glob whose only mutant survived FAILS (within budget, nothing killed)",
           backend_case(beta=(1, 1)), 1)

    # Web = total-count + diff-aware.
    expect("web: diff in scope + total 0 mutants FAILS (total-count)", web_case([V1], WEB + "/" + V1, 0), 1)
    expect("web: diff in scope + total > 0 PASSES", web_case([V1], WEB + "/" + V1, 3), 0)
    expect("web: diff NOT in scope PASSES regardless of report", web_case([V1], WEB + "/other.ts", 0), 0)

    # The crypto project is a SECOND, independently-checked scope (cr-admin-web-extraction, ADR 0065) — the
    # gate had to split when the code did, because Stryker's runner is the project's own vitest. Its arm must
    # be exercised on its own: a check that only ever read src/web/'s manifest would pass every case above while
    # silently never looking at the crypto scope at all.
    CV1 = "src/v1.ts"
    expect("crypto: diff in the crypto scope + total 0 mutants FAILS",
           web_case([CV1], CRYPTO + "/" + CV1, 0, project=CRYPTO), 1)
    expect("crypto: diff in the crypto scope + total > 0 PASSES",
           web_case([CV1], CRYPTO + "/" + CV1, 3, project=CRYPTO), 0)
    # Per-project, NOT a union: web's report is full, but the diff hit crypto's scope and crypto measured
    # nothing. A union over both reports would call this green.
    expect("cross-project: a full web report does NOT excuse an empty crypto run",
           web_case([CV1], CRYPTO + "/" + CV1, 0, project=CRYPTO,
                    other={"project": WEB, "mutate": [V1], "total_mutants": 9}), 1)
    # ...and the mirror, so the rule is symmetric rather than an accident of ordering.
    expect("cross-project: a full crypto report does NOT excuse an empty web run",
           web_case([V1], WEB + "/" + V1, 0, project=WEB,
                    other={"project": CRYPTO, "mutate": [CV1], "total_mutants": 9}), 1)

    # A project whose manifest is gone FAILS on its own: a moved project must not leave the check reading the
    # other one and saying OK (cr-repo-layout-src).
    expect("missing: a crypto project without its manifest FAILS though web's run is full",
           web_case([V1], WEB + "/" + V1, 3, missing=CRYPTO), 1)
    expect("missing: a web project without its manifest FAILS though crypto's run is full",
           web_case([CV1], CRYPTO + "/" + CV1, 3, project=CRYPTO, missing=WEB), 1)

    # The asymmetry itself, on the SAME 'one member empty, a sibling full' shape:
    expect("asymmetry — backend REJECTS one-empty-sibling", backend_case(beta=(0, 0)), 1)
    expect("asymmetry — web ACCEPTS one-empty-sibling (total > 0)",
           web_case([V1, V2], WEB + "/" + V1, 3), 0)

    failed = [n for n, ok in results if not ok]
    if failed:
        print(f"::error::mutation meta-check selftest FAILED — the asymmetry is not as documented: {failed}")
        return 1
    print(f"selftest OK: {len(results)} cases — backend is per-glob, web is total-count + diff-aware.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
