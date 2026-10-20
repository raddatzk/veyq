#!/usr/bin/env python3
"""Guard that `scripts/run_web_e2e.py` cannot report success having run zero e2e files.

**What it asserts.** Three things, all about the orchestrator's discovery step:
  1. `discover()` finds at least one `*.e2e.test.ts` in the real `src/web/` tree, and everything it returns really is
     an e2e file. This half exists so the guard cannot pass by pointing at nothing — the same failure it guards.
  2. `discover()` on a tree with no e2e files **raises** `NoE2eFilesError` rather than returning `[]`. An empty
     list would flow through `main()` as "0 files, 0 failures, exit 0".
  3. Nothing swallows that error: the no-arg path in `main()` goes through `discover()`, and the module's only
     `except NoE2eFilesError` re-raises it as a `SystemExit`. A raise that is caught and logged would put the
     defect back.

**The defect that bought it.** An orchestrator reporting green because it ran nothing is indistinguishable from
one whose suites all passed, and this exact claim has already been false once in this repo: ADR 0040's
*Correction (2026-07-14)* records a coverage table headed **Measured** that claimed `run_web_e2e.py` ran when the
file did not exist, and four `web`/`e2e` coverage refs were riding on it. Those refs come back only on a green CI
run — so "green" must be unable to mean "nothing ran". `changes/README.md` § a bite test must tell "the guard
held" apart from "nothing ran" is the general form.

**Its limit.** It reaches discovery and nothing downstream. A green run here does NOT prove that the stack came
up, that a backend booted, that a database was fresh, or that any assertion in any suite executed — those need
Docker and a built jar, so they cannot live in the buildless `validate_*` glob, and the `web-e2e` CI job is their
only enforcement: a red job is that guard. Assertion 3 is also **textual**, not semantic: it reads the module's
source for the swallow-shapes it knows, so a novel way to swallow the error (catching a base class, a bare
`except`) would pass it. That is the price of staying buildless; the two behavioural assertions above are not
textual.

Note it does not copy the repo the way `validate_guard_gaps.py` does. It does not need to: `discover()` takes its
root as a parameter, so an empty `tempfile` directory is already the throwaway tree, and nothing is ever injected
into the live one.
"""
import importlib.util
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
ORCHESTRATOR = ROOT / "scripts" / "run_web_e2e.py"

errors: list[str] = []


def load(path: pathlib.Path):
    spec = importlib.util.spec_from_file_location("veyq_run_web_e2e", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"E2E DISCOVERY VALIDATION FAILED:\n  - cannot import {path.relative_to(ROOT)}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


if not ORCHESTRATOR.is_file():
    print("E2E DISCOVERY VALIDATION FAILED:")
    print(f"  - {ORCHESTRATOR.relative_to(ROOT)} is missing — the web-e2e CI job calls it directly")
    sys.exit(1)

orchestrator = load(ORCHESTRATOR)
source = ORCHESTRATOR.read_text()
found: list = []

# 1 — the real tree must yield e2e files, and only e2e files.
try:
    found = orchestrator.discover()
except Exception as e:  # noqa: BLE001 — any failure here is a finding, not a crash
    errors.append(f"discover() failed on the real src/web/ tree: {e!r}")
else:
    if not found:
        errors.append("discover() returned an empty list for the real src/web/ tree")
    for path in found:
        if not path.name.endswith(".e2e.test.ts"):
            errors.append(f"discover() returned a non-e2e file: {path}")

# 2 — an empty tree must RAISE, not return [].
with tempfile.TemporaryDirectory(prefix="veyq-e2e-guard-") as tmp:
    empty = pathlib.Path(tmp)
    (empty / "src").mkdir()
    try:
        result = orchestrator.discover(empty)
    except orchestrator.NoE2eFilesError:
        pass
    except Exception as e:  # noqa: BLE001
        errors.append(f"discover() on an empty tree raised {type(e).__name__}, expected NoE2eFilesError: {e}")
    else:
        errors.append(
            f"discover() on an empty tree returned {result!r} instead of raising NoE2eFilesError — "
            "a zero-file run would be scored as success"
        )

# 3 — the no-arg path must go through discover(), and the error must not be swallowed.
if not re.search(r"else\s+discover\(\)", source):
    errors.append("main()'s no-argument path does not read `else discover()` — discovery may have been inlined")
for match in re.finditer(r"except\s+NoE2eFilesError[^\n]*:\n((?:[ \t]+.*\n|\n)*)", source):
    if "SystemExit" not in match.group(1):
        errors.append("an `except NoE2eFilesError` block does not re-raise as SystemExit — the error is swallowed")

if errors:
    print("E2E DISCOVERY VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"e2e discovery OK: {len(found)} suite(s) discovered; an empty tree raises instead of reporting success")
