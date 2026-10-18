#!/usr/bin/env python3
"""Hold the API fuzzing's baseline (`infra/fuzz/baseline.json`) to its rules (ADR 0091 decision 9).

**What it asserts.** The baseline is the list of Schemathesis failures a run accepts (`scripts/run_api_fuzz.py`); every
entry of it:

1. carries a non-empty `reason` — a known failure nobody can explain is a failure nobody decided to accept;
2. never has `check: not_a_server_error` — a 5xx is fixed, never recorded (owner decision Q2 of cr-api-fuzzing);
3. carries no `expires` — the only key Schemathesis itself reads; a gate turns red on a commit, never on a calendar day;
4. names an `operation` (`METHOD /path`) that a spec of `contracts/api` (`contract_specs.specs()`) declares — an entry
   for an operation that is gone is an exception for nothing, and it hides that the list was never pruned;
5. names, when its `reason` defers the fix (the word "defer"), an inbox row `#<n>` — and every `#<n>` a reason names is
   a row of `docs/inbox.md` whose status is not closed (`done`, `dissolved`, `closed`, `dropped`, `superseded`): a
   deferral to finished work is a deferral to nothing.

The file must be Schemathesis' baseline format (`format_version` 1, a list of `entries`, each with the tool's four
identity fields), so a hand edit that breaks it is red here before the run reads it.

**The defect that bought it.** No incident — built with cr-api-fuzzing, before the first entry, because the baseline is
the one place a fuzz finding can be silenced without a code change, and ADR 0091 decision 9 promises it never holds a
5xx and never expires.

**Its limit.** It reads the file, not the run: it does not prove an entry still reproduces (`--baseline-prune` does,
by hand), that a `reason` is TRUE, or that a failure recorded under another check is no 5xx in disguise (a
`status_code_conformance` entry for a `500` passes rules 1–5; `not_a_server_error` reports the same response red in the
same run, and that is the check this guard keeps out — pinned open by `validate_guard_gaps.py` case 84). A deferral
that does not say "defer" is not recognised as one, and a status cell whose first word is no closed marker counts as
open.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import contract_specs

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "infra" / "fuzz" / "baseline.json"
INBOX = ROOT / "docs" / "inbox.md"
METHODS = ("get", "put", "post", "delete", "patch", "head", "options")
IDENTITY = ("operation", "check", "failure", "signature")
CLOSED = ("done", "dissolved", "closed", "dropped", "superseded")
ROW = re.compile(r"^\|\s*(\d+)\s*\|[^|]*\|[^|]*\|\s*([^|]*)\|", re.M)
ROW_REF = re.compile(r"#(\d+)\b")


def declared_operations() -> set[str]:
    ops = set()
    for spec in contract_specs.specs():
        for path, item in (contract_specs.load(contract_specs.key(spec)).get("paths") or {}).items():
            for method in item:
                if method in METHODS:
                    ops.add(f"{method.upper()} {path}")
    return ops


def inbox_rows(text: str) -> dict[str, str]:
    """Row number -> the first word of its status cell, lower-cased, without markup."""
    rows = {}
    for number, status in ROW.findall(text):
        word = re.sub(r"[^a-z]", "", (status.strip().split() or [""])[0].lower())
        rows[number] = word
    return rows


def problems(doc: object, operations: set[str], rows: dict[str, str]) -> list[str]:
    if not isinstance(doc, dict) or doc.get("format_version") != 1 or not isinstance(doc.get("entries"), list):
        return ["not Schemathesis' baseline format: an object with `format_version: 1` and a list of `entries`"]
    out = []
    for i, entry in enumerate(doc["entries"]):
        if not isinstance(entry, dict) or not all(isinstance(entry.get(k), str) for k in IDENTITY):
            out.append(f"entry {i}: not a baseline entry (needs the string fields {', '.join(IDENTITY)})")
            continue
        name = f"entry {i} ({entry.get('id', '?')}: {entry['check']} on {entry['operation']})"
        reason = entry.get("reason")
        if not isinstance(reason, str) or not reason.strip():
            out.append(f"{name}: no `reason` — say why this failure is accepted, or fix it")
            reason = ""
        if entry["check"] == "not_a_server_error":
            out.append(f"{name}: a 5xx is never recorded in the baseline — fix it (ADR 0091 decision 9)")
        if "expires" in entry:
            out.append(f"{name}: carries `expires` — no entry expires by date; a gate turns red on a commit")
        if entry["operation"] not in operations:
            out.append(f"{name}: `{entry['operation']}` is no operation of contracts/api")
        refs = ROW_REF.findall(reason)
        if re.search(r"\bdefer", reason, re.I) and not refs:
            out.append(f"{name}: the reason defers the fix but names no inbox row `#<n>`")
        for ref in refs:
            status = rows.get(ref)
            if status is None:
                out.append(f"{name}: names inbox row #{ref}, which docs/inbox.md does not have")
            elif status in CLOSED:
                out.append(f"{name}: names inbox row #{ref}, which is {status} — a deferral to closed work")
    return out


def main() -> int:
    try:
        doc = json.loads(BASELINE.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        print(f"validate_api_fuzz_baseline: {BASELINE.relative_to(ROOT)} unreadable: {e}")
        return 1
    found = problems(doc, declared_operations(), inbox_rows(INBOX.read_text(encoding="utf-8")))
    if found:
        print(f"validate_api_fuzz_baseline: {len(found)} problem(s) in {BASELINE.relative_to(ROOT)}")
        for p in found:
            print(f"  - {p}")
        return 1
    print(f"validate_api_fuzz_baseline: {len(doc['entries'])} entr(y/ies) in {BASELINE.relative_to(ROOT)}, each "
          f"reasoned, none a 5xx, none expiring, every operation declared")
    return 0


if __name__ == "__main__":
    sys.exit(main())
