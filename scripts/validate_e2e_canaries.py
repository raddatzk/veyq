#!/usr/bin/env python3
"""Guard that every live e2e suite contributes canaries to the wire-assertion sweep.

**What it asserts.** For every file `scripts/run_web_e2e.py`'s own `discover()` returns — the sweep's whole
population, read from the orchestrator rather than re-globbed here, so the two can never disagree about what a
suite is — that the file both **imports** `recordCanaries` from `src/test/canaries` and **calls** it. And that
the recorder module itself is still there. Import without a call is the shape this most plausibly rots into (a
suite refactored until the last `recordCanaries(...)` disappears while the import lingers), so both halves are
checked, not one.

This is the completeness half of `cc.security.wire-assertion`, and the only half that is decidable **without
Docker**. The sweep proper needs a booted backend and a real database, so it lives in the `web-e2e` CI job
(ADR 0053, ADR 0040) — but "every suite contributes" is a property of the source text alone, so it belongs in
the buildless glob where it runs on every push. It is the same split `validate_e2e_discovery.py` already makes:
that guard proves the orchestrator cannot run zero *files*, this one proves it cannot sweep zero *values*.

**The defect that bought it.** No incident — built with cr-wire-assertion-db-sweep, because the failure it
prevents is structurally invisible. A sweep over an empty canary set passes: it proves absence of nothing, and
reports the same green as a sweep that proved something. A new e2e suite that simply never calls the recorder
puts the whole flow it drives outside the sensor with no signal anywhere. `changes/README.md` § promote one-shot
proofs is the general form — the CR's "every suite contributes canaries" is a structural invariant, so it ships
as a permanent guard instead of a tally in prose. (The orchestrator additionally hard-fails on a missing or
empty canary file at run time; this guard is what makes that failure impossible to reach by accident rather
than only reportable after a 10-minute Docker run.)

**Its limit.** Textual, and shallower than the property it stands for. It reads the suite's source for an
import and a call — it cannot see whether that call is on a reachable path, whether the value handed over was
genuinely *unsealed* (a suite could record a literal it typed itself and satisfy this guard completely), or
whether the recorded envelope covers the flow the suite drives. Nor does it check the OTHER direction: that
what is recorded is not the sealed carrier rather than its plaintext (`recordCanaries(node)` instead of
`node.name`), which the sweep catches loudly at run time and this guard does not see at all. Assertion strength
here is the `web-e2e` job's business, and a green run of this guard makes no coverage claim whatsoever — it
only makes the empty-set failure mode unreachable.
"""
import importlib.util
import pathlib
import re
import sys

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent
ORCHESTRATOR = ROOT / "scripts" / "run_web_e2e.py"
RECORDER = repo_layout.WEB / "src" / "test" / "canaries.ts"

# The import may be `import { recordCanaries }` or list siblings; the path is relative, so anchor on the module
# stem rather than on a fixed number of `../`.
IMPORT = re.compile(r"import\s*\{[^}]*\brecordCanaries\b[^}]*\}\s*from\s*['\"][^'\"]*/test/canaries['\"]")
CALL = re.compile(r"\brecordCanaries\s*\(")

errors: list[str] = []


def load(path: pathlib.Path):
    spec = importlib.util.spec_from_file_location("veyq_run_web_e2e_canaries", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"E2E CANARY VALIDATION FAILED:\n  - cannot import {path.relative_to(ROOT)}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


if not ORCHESTRATOR.is_file():
    print("E2E CANARY VALIDATION FAILED:")
    print(f"  - {ORCHESTRATOR.relative_to(ROOT)} is missing — it owns the suite population this guard checks")
    sys.exit(1)

if not RECORDER.is_file():
    errors.append(f"{RECORDER.relative_to(ROOT)} is missing — no suite can derive canaries without it")

suites: list[pathlib.Path] = []
try:
    suites = load(ORCHESTRATOR).discover()
except Exception as e:  # noqa: BLE001 — any failure here is a finding, not a crash
    errors.append(f"discover() failed, so the suite population is unknown: {e!r}")

for suite in suites:
    body = suite.read_text()
    rel = suite.relative_to(ROOT).as_posix()
    if not IMPORT.search(body):
        errors.append(f"{rel} does not import recordCanaries from src/test/canaries — it sweeps nothing")
    elif not CALL.search(body):
        errors.append(f"{rel} imports recordCanaries but never calls it — it sweeps nothing")

if errors:
    print("E2E CANARY VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"e2e canaries OK: all {len(suites)} live suite(s) derive canaries for the wire-assertion sweep")
