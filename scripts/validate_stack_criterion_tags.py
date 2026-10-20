#!/usr/bin/env python3
"""Every test of lane L1's browser suite names the acceptance criteria it covers, and every `stack` coverage ref names
a test tagged with its criterion (ADR 0091 decision 3; cr-stack-e2e phase 4).

**What it asserts.** The anchor set is every test declared in `e2e/tests/**/*.ts` — found by reading the files, never
listed — and every coverage file under `docs/coverage/features/`:

  1. **Every test carries a tag, and every tag names a criterion.** A test is a call `test(…)` (or `test.only`,
     `.skip`, `.fixme`, `.fail`) whose first argument is a string literal; its tags are the string literals of its
     options' `tag:` (one, or an array) and every `@word` in its title, as Playwright reads them. Each tag must be
     `@<use-case>:<criterion-id>` and name a criterion that use-case's coverage file declares. There is no second
     wording of a criterion (ADR 0091 decision 3): the tag points at the one sentence, and a tag whose use-case or
     criterion is misspelt — or was renamed under it — is red, never a label nobody resolves.
  2. **Every `stack` ref names a tagged test.** A ref under `coverage.stack.<level>` of criterion C in use-case U is a
     path relative to `e2e/`; that file must be one of the suite's and must declare a test tagged `@U:C`. A ref to a
     file that only exercises the criterion on the way to another is a claim nothing in the file makes.
  3. **Every use-case a tag names addresses `stack`** — a `stack` ref on one of its criteria, or a use-case-level
     `gaps.stack` saying why there is none. Until a CI run admits `stack` (ADR 0040, `validate_coverage.py`'s
     `ADMITTED`), that gap is where the suite's coverage waits, named; this keeps it from being silent.
  The guard prints how many files, tests and tags it read; zero tests is red, so a moved suite cannot pass as an
  empty one.

**The defect that bought it.** No incident — built with cr-stack-e2e phase 4 because ADR 0091 decision 3 makes the tag
the only link between a browser scenario and the criterion it proves, and nothing else reads it: Playwright accepts any
string as a tag, `validate_coverage.py` resolves a `stack` ref to a file and not to what the file claims, and the two
wordings (the tag and the criterion id) would drift apart silently on the first rename.

**Its limit — what a green run does NOT prove.** It reads TypeScript as text: a test declared through an alias of
`test` (`const it = test`, `test as setup`) is not seen — an import that renames `test` is red for that reason, but a
local alias is invisible; a tag built at run time (a variable, a template literal, a spread) is not a literal, so its
test reads as untagged (red, never a silent pass); a tag on a `test.describe` is not read, so tests that rely on one
read as untagged (red). It proves a tag names a criterion, never that the test exercises what the criterion's
sentence says — that is the reader's, as for every coverage ref (`validate_coverage.py` § reference substance). And it
does not decide whether `stack` refs make a claim: `validate_coverage.py` rejects them until `stack` is admitted.
"""
from __future__ import annotations  # `X | None` — the guards run on the host's Python 3.9

import pathlib
import re
import sys

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import repo_layout  # noqa: E402 - where the deployment files and the test harnesses live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent
E2E = repo_layout.E2E_DIR
TESTS = E2E / "tests"
COVERAGE = ROOT / "docs" / "coverage" / "features"
SURFACE = "stack"

# `test(`, `test.only(` … — not `test.describe(`, `test.beforeAll(`, `test.step(`, whose first argument is no test.
DECLARATION = re.compile(r"(?<![\w.$])test(?:\.(?:only|skip|fixme|fail))?\s*\(")
RENAMED_TEST = re.compile(r"\btest\s+as\s+\w+")
TAG = re.compile(r"^@(?P<uc>uc\.[a-z0-9-]+\.[a-z0-9-]+):(?P<criterion>[a-z0-9][a-z0-9-]*)$")
TITLE_TAG = re.compile(r"(?:^|\s)(@\S+)")
QUOTES = "'\"`"

errors: list[str] = []


def rel(path: pathlib.Path) -> str:
    return str(path.relative_to(ROOT))


def string_at(text: str, i: int) -> tuple[str, int] | None:
    """The JS string literal starting at `i` (its decoded body, the index after it), or None if none starts there."""
    if i >= len(text) or text[i] not in QUOTES:
        return None
    quote, j, body = text[i], i + 1, []
    while j < len(text):
        c = text[j]
        if c == "\\" and j + 1 < len(text):
            body.append(text[j + 1])
            j += 2
            continue
        if c == quote:
            return "".join(body), j + 1
        if quote == "`" and text.startswith("${", j):
            return None  # an interpolated title or tag is not a literal
        body.append(c)
        j += 1
    return None


def skip_ws(text: str, i: int) -> int:
    while i < len(text) and text[i].isspace():
        i += 1
    return i


def braces_at(text: str, i: int) -> tuple[str, int] | None:
    """The balanced `{…}` starting at `i`, string literals respected."""
    if i >= len(text) or text[i] != "{":
        return None
    depth, j = 0, i
    while j < len(text):
        lit = string_at(text, j)
        if lit is not None:
            j = lit[1]
            continue
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1], j + 1
        j += 1
    return None


def option_tags(options: str) -> list[str] | None:
    """The literal tags of an options object's `tag:`; [] without one; None when it is not made of literals."""
    m = re.search(r"(?<![\w$])tag\s*:\s*", options)
    if not m:
        return []
    i = m.end()
    lit = string_at(options, i)
    if lit is not None:
        return [lit[0]]
    if i < len(options) and options[i] == "[":
        tags, i = [], skip_ws(options, i + 1)
        while i < len(options) and options[i] != "]":
            lit = string_at(options, i)
            if lit is None:
                return None
            tags.append(lit[0])
            i = skip_ws(options, lit[1])
            if i < len(options) and options[i] == ",":
                i = skip_ws(options, i + 1)
        return tags
    return None


def declared_tests(path: pathlib.Path) -> list[tuple[str, list[str], int]]:
    """Every test the file declares: (title, tags, line). Reports a test whose tags are not literals."""
    text = path.read_text(encoding="utf-8")
    if RENAMED_TEST.search(text):
        errors.append(f"{rel(path)}: imports `test` under another name — this guard reads `test(…)` declarations "
                      "only, so tests declared through the alias would go unread. Declare them with `test`.")
    tests = []
    for m in DECLARATION.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        i = skip_ws(text, m.end())
        title = string_at(text, i)
        if title is None:
            if i < len(text) and text[i] in QUOTES:
                errors.append(f"{rel(path)}:{line}: a test whose title is not a literal — its tags cannot be read")
            continue  # `test.skip()` / `test.fail()` with no title: a modifier, not a declaration
        tags = [t for t in TITLE_TAG.findall(title[0])]
        i = skip_ws(text, title[1])
        if i < len(text) and text[i] == ",":
            options = braces_at(text, skip_ws(text, i + 1))
            if options is not None:
                found = option_tags(options[0])
                if found is None:
                    errors.append(f"{rel(path)}:{line}: test {title[0]!r} gives `tag:` as something other than "
                                  "string literals — a tag must be readable without running the suite")
                    found = []
                tags += found
        tests.append((title[0], tags, line))
    return tests


def coverage_files() -> dict[str, dict]:
    files: dict[str, dict] = {}
    for path in sorted(COVERAGE.glob("*/use-cases/*.yaml")):
        doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        files[str(doc.get("useCase") or path.stem)] = {"path": path, "doc": doc}
    return files


def main() -> int:
    if not TESTS.is_dir():
        print(f"STACK CRITERION TAGS FAILED:\n  - {rel(TESTS)} does not exist — the suite moved, and this guard read "
              "nothing", file=sys.stderr)
        return 1
    coverage = coverage_files()
    if not coverage:
        print(f"STACK CRITERION TAGS FAILED:\n  - no coverage file under {rel(COVERAGE)}", file=sys.stderr)
        return 1
    criteria = {uc: {str(c.get("id")) for c in (f["doc"].get("acceptanceCriteria") or []) if isinstance(c, dict)}
                for uc, f in coverage.items()}

    tagged: dict[str, set[str]] = {}  # e2e-relative file -> every tag one of its tests carries
    named_use_cases: set[str] = set()
    files = sorted(TESTS.rglob("*.ts"))
    n_tests = n_tags = 0
    for path in files:
        where = str(path.relative_to(E2E))
        tagged[where] = set()
        for title, tags, line in declared_tests(path):
            n_tests += 1
            if not tags:
                errors.append(f"{rel(path)}:{line}: test {title!r} carries no tag — every scenario names the "
                              "criterion it covers as `@<use-case>:<criterion-id>` (ADR 0091 decision 3)")
            for tag in tags:
                n_tags += 1
                m = TAG.match(tag)
                if not m:
                    errors.append(f"{rel(path)}:{line}: tag {tag!r} on {title!r} is not `@<use-case>:<criterion-id>`")
                    continue
                uc, criterion = m.group("uc"), m.group("criterion")
                if uc not in criteria:
                    errors.append(f"{rel(path)}:{line}: tag {tag!r} names use-case {uc}, which has no coverage file "
                                  f"under {rel(COVERAGE)}")
                    continue
                if criterion not in criteria[uc]:
                    errors.append(f"{rel(path)}:{line}: tag {tag!r} names criterion {criterion!r}, which "
                                  f"{rel(coverage[uc]['path'])} does not declare (its ids: "
                                  f"{', '.join(sorted(criteria[uc]))})")
                    continue
                tagged[where].add(tag)
                named_use_cases.add(uc)
    if n_tests == 0:
        errors.append(f"no test declared under {rel(TESTS)} ({len(files)} file(s)) — the guard checked nothing")

    n_refs = 0
    for uc, f in sorted(coverage.items()):
        for c in f["doc"].get("acceptanceCriteria") or []:
            if not isinstance(c, dict):
                continue
            levels = ((c.get("coverage") or {}).get(SURFACE) or {})
            for level, refs in (levels.items() if isinstance(levels, dict) else []):
                for ref in refs or []:
                    n_refs += 1
                    want = f"@{uc}:{c.get('id')}"
                    if str(ref) not in tagged:
                        errors.append(f"{rel(f['path'])}: {SURFACE}.{level} ref {ref} on {c.get('id')} is not a file "
                                      f"of the suite ({rel(TESTS)})")
                    elif want not in tagged[str(ref)]:
                        errors.append(f"{rel(f['path'])}: {SURFACE}.{level} ref {ref} on {c.get('id')} names a file "
                                      f"none of whose tests is tagged {want}")
    for uc in sorted(named_use_cases):
        doc = coverage[uc]["doc"]
        has_ref = any(isinstance(c, dict) and (c.get("coverage") or {}).get(SURFACE)
                      for c in doc.get("acceptanceCriteria") or [])
        has_gap = SURFACE in (doc.get("gaps") or {})
        if not (has_ref or has_gap):
            errors.append(f"{rel(coverage[uc]['path'])}: a test of lane L1 is tagged with this use-case's criteria, "
                          f"but the file says nothing on `{SURFACE}` — cite the spec, or name it under `gaps: "
                          f"{SURFACE}:` until a CI run admits the surface (ADR 0040)")

    if errors:
        print("STACK CRITERION TAGS FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    print(f"stack criterion tags OK: {n_tests} test(s) in {len(files)} file(s), {n_tags} tag(s) naming criteria of "
          f"{len(named_use_cases)} use-case(s); {n_refs} `{SURFACE}` ref(s), each naming a tagged test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
