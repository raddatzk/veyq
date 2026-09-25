#!/usr/bin/env python3
"""Every class the backend mutation gate mutates must also TRIGGER it.

**What it asserts.** For each entry in `backend/pitest.scope.json` (`targetClasses` + `targetTests`), derive
the `.kt` file it names — package → directory, class → filename, a trailing `*` stripped — and assert that
file (a) exists on disk and (b) is listed in **both** `paths:` blocks of
`.github/workflows/mutation-backend.yml`. The two blocks are checked **separately, not as a union**: Actions
supports no YAML anchors, so the PR filter and the main-branch backstop are hand-duplicated and can drift
apart — which is exactly the failure this guard exists for. A **subset** check: the blocks legitimately also
list the manifest, `build.gradle.kts`, the meta-checks and the workflow itself, and extra entries are not this
guard's business.

**The defect that bought it — measured, not hypothetical.** `a8cc4ce4` (2026-07-19) created the workflow when
the scope held three classes; the list was correct. `b15807a7` (2026-07-20) added `ShareAccessPolicy` and
`MembershipPolicy` to the manifest and did **not** touch the workflow (`git show --stat b15807a7 | grep -c
mutation-backend.yml` → 0). From that day a PR touching **only** one of those two classes never triggered the
assertion-strength gate at all — the check silently did not run on exactly the change it exists for. The
workflow's own comment asserted the opposite (*"a new policy enters via a pitest.scope.json edit, which is
listed here, so adding one always triggers the gate"*): true for the adding commit, false for every later edit
to that class. The manual list broke on the first policy added after it existed, one day later. Inbox #114.

Enforcing the manifest's *"an EXPLICIT list, never a package sweep"* rule falls out for free: a package glob
(`com.veyq.app.features.*`) derives no filename and is rejected here with that reason named.

**Its limit — a path filter cannot cover transitive dependencies.** `MembershipPolicy` imports
`shared.membership.MembershipEntity`; `RemovalPolicy` imports that plus `shared.scope.ScopeEntity`. A change
to those moves a mutation score without touching any listed path, and no path-based trigger list can see it —
this is a principled limit of the mechanism, not a hole to plug (pinned as a case in
`validate_guard_gaps.py`). Two narrower ones: it proves a path is **listed**, never that GitHub Actions
actually dispatches on it (a malformed filter elsewhere, or a disabled workflow, is outside); and it says
nothing about whether the gate, once triggered, is strict enough — that is
`mutation_meta_check_backend.py`'s job and the build's `mutationThreshold`.

Buildless and diff-free, so it belongs in the trust-chain glob (unlike its two `mutation_meta_check*.py`
siblings, which need a built report and therefore run from the mutation jobs).
"""
from __future__ import annotations  # `str | None` below — the repo's guards run on Python 3.9

import json
import pathlib
import sys

try:
    import yaml
except ImportError:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "backend" / "pitest.scope.json"
WORKFLOW = ROOT / ".github" / "workflows" / "mutation-backend.yml"

SOURCE_ROOTS = {"targetClasses": "backend/src/main/kotlin", "targetTests": "backend/src/test/kotlin"}

errors: list[str] = []


def derive_path(entry: str, source_root: str) -> str | None:
    """A PIT class glob → the repo-relative .kt file that declares it. `None` when the entry names no single
    file (a package sweep), which the manifest forbids anyway."""
    name = entry.rstrip("*")
    name = name.split("$", 1)[0]  # a nested class lives in its outer class's file
    if not name or name.endswith("."):
        return None
    return f"{source_root}/{name.replace('.', '/')}.kt"


def trigger_blocks() -> dict[str, list[str]]:
    """The workflow's `paths:` filters, keyed by trigger. NOTE: PyYAML is YAML 1.1, where the bare key `on` is
    the BOOLEAN True — `data["on"]` raises KeyError on a perfectly valid workflow. This is not a typo."""
    data = yaml.safe_load(WORKFLOW.read_text())
    on = data.get("on", data.get(True)) or {}
    return {trigger: (on.get(trigger) or {}).get("paths") or [] for trigger in ("pull_request", "push")}


def main() -> int:
    scope = json.loads(MANIFEST.read_text())
    blocks = trigger_blocks()

    for trigger, paths in blocks.items():
        if not paths:
            errors.append(f"{WORKFLOW.name}: trigger '{trigger}' has no `paths:` filter — the gate would run "
                          f"on every push (or not at all). Expected the scope's files to be listed there.")

    checked = 0
    for key, source_root in SOURCE_ROOTS.items():
        for entry in scope.get(key) or []:
            rel = derive_path(entry, source_root)
            if rel is None:
                errors.append(f"pitest.scope.json: '{entry}' ({key}) names no single class — a package sweep. "
                              f"The manifest's own rule is an EXPLICIT list (ADR 0045 §2); it also leaves this "
                              f"guard unable to derive a trigger path.")
                continue
            checked += 1

            if not (ROOT / rel).exists():
                errors.append(f"pitest.scope.json: '{entry}' ({key}) derives {rel}, which does not exist — a "
                              f"renamed or moved class. PIT would mutate nothing for it.")

            for trigger, paths in blocks.items():
                if paths and rel not in paths:
                    errors.append(f"{WORKFLOW.name}: '{rel}' is in the mutate scope ('{entry}') but missing "
                                  f"from the '{trigger}' `paths:` filter — a change to it alone would never "
                                  f"trigger the mutation gate (inbox #114).")

    if errors:
        print("MUTATION TRIGGER PATH VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(f"mutation trigger paths OK: {checked} scoped file(s) exist and are listed in both "
          f"`paths:` filters ({', '.join(blocks)})")
    print("  scope: the scope's OWN files trigger the gate. NOT transitive dependencies — see the docstring.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
