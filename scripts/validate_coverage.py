#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each is well-formed (every acceptance
criterion is covered by at least one test, keyed by area then level; uncovered surfaces are explicit
gaps), and that every referenced test actually exists, so dead refs fail early:
    backend -> "ClassName.method", resolved against *that* class (test file whose stem is ClassName
               must exist, declare method, AND that method must have a non-empty body — an empty `{}`
               block is rejected; a Kotlin expression body `= expr` counts, it delegates to real code)
    web     -> a file path (relative to web/) that both exists AND is a test file (*.test|*.spec)

`http` is NOT a coverage surface (ADR 0040): the `.http` files remain IntelliJ dev tooling but run in
no CI job, so they make no coverage claim — the same behaviour is proven by the backend slice tests.

Class-scoped resolution: a "ClassName.method" ref is checked against the test file whose stem is
ClassName, not a flat set of every method name on the surface — so a ref pointing at the wrong class
fails even if some other class happens to have a method of that (generic) name.

Reference *substance*, not just resolution: a ref that resolves can still point at nothing that runs —
a web ref at a non-test file (a real `package.json` once passed), a backend ref at an empty `fun` body.
Both degenerate cases are rejected here. This does NOT prove the test's assertion is *correct* — a body
that runs but asserts nothing (oracle correctness) stays ADR 0045's job (mutation score), never this
guard's. The line is deliberate: "is it a test file / does it have a body" is decidable and cheap;
"does the body assert the right thing" is not, and pretending otherwise would oversell the green.

Coverage shape (per acceptance criterion):
    coverage:
      <area>:            # backend | web
        <level>:         # unit | integration | e2e
          - <ref>        # see resolution rules above
    gaps:
      <area>: <reason>   # surfaces with NO coverage ref for this use-case (an area that IS covered
                         # may not appear here — overlap is a stale gap and fails, ADR 0040)
"""
import pathlib
import re
import sys

try:
    import yaml
except ImportError:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURES = ROOT / "docs" / "features"
COVERAGE = ROOT / "docs" / "coverage" / "features"
BACKEND_TESTS = ROOT / "backend" / "src" / "test"
WEB = ROOT / "web"

# The surface set, and the whole of it: a surface counts when a CI job actually runs its tests — the ADR 0040
# principle, applied by rule rather than by name (the claim follows the build). Every use-case owes every
# surface here; adding one only ever *permits* refs, so a new surface is one line plus its resolution rule.
SURFACES = {"backend", "web"}
KNOWN_LEVELS = {"unit", "integration", "e2e"}

errors: list[str] = []


KOTLIN_FUN = r"fun\s+`?([A-Za-z0-9_]+)`?\s*\("


def _kotlin_funs(src: str):
    """Yield (method-name, has_substance) for each `fun` declared in src.

    has_substance is False only for a **block body** that is empty or comment-only (`fun x() {}`).
    A Kotlin **expression body** (`fun x() = assertWriteGate(...)`) always counts — it delegates to
    real code, so treating it as "empty" would false-positive every delegating test (the edge that
    made naive `{}`-only detection wrong). Body-less abstract/expected decls yield False but are never
    ref targets, so it does not matter.

    Regex-with-brace-matching, not a Kotlin parser: the param list is skipped by balanced-paren scan
    (so a default value's own `()` doesn't end it early); the body delimiter is the first `{` or `=`
    after the params, bounded by the enclosing `}` so a body-less decl can't borrow the next fun's body.
    """
    for m in re.finditer(KOTLIN_FUN, src):
        name = m.group(1)
        i = m.end() - 1  # at the '(' of the param list
        depth = 0
        while i < len(src):  # skip a balanced param list (default values may nest parens)
            if src[i] == "(":
                depth += 1
            elif src[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        j = i + 1  # first char past ')'
        while j < len(src) and src[j] not in "{=}":  # hop over a `: ReturnType` to the body delimiter
            j += 1
        if j >= len(src) or src[j] == "}":  # no body (abstract/expected) — not a ref target
            yield name, False
        elif src[j] == "=":  # expression body — delegates to real code, always substance
            yield name, True
        else:  # '{' block body — substance iff non-whitespace, non-comment content remains
            depth = 0
            k = j
            while k < len(src):
                if src[k] == "{":
                    depth += 1
                elif src[k] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                k += 1
            body = re.sub(r"//[^\n]*", "", src[j + 1:k])
            body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
            yield name, body.strip() != ""


def _stem_methods(surface: str, files) -> dict[str, dict[str, bool]]:
    """Map each test file's stem -> {method name -> has_substance} for the funs it declares.

    Class-scoped ref resolution keys on the file stem, so it assumes stems are unique within a
    surface; two files sharing a stem would silently merge their methods and re-open the very
    cross-class hole this keying closes. That invariant holds today but isn't otherwise enforced,
    so collisions are reported as errors rather than assumed away.

    Per-file helper/stub/companion classes lump under the file stem; harmless because they are
    never ref targets, and stem-keying ignores them for free.
    """
    by_stem: dict[str, dict[str, bool]] = {}
    sources: dict[str, list[str]] = {}
    for f in files:
        funs = by_stem.setdefault(f.stem, {})
        for name, has_substance in _kotlin_funs(f.read_text()):
            funs[name] = funs.get(name, False) or has_substance  # an overload with any real body counts
        sources.setdefault(f.stem, []).append(rel(f))
    for stem, paths in sources.items():
        if len(paths) > 1:
            errors.append(f"{surface} test stem '{stem}' is not unique (breaks class-scoped "
                          f"resolution): {', '.join(sorted(paths))}")
    return by_stem


def backend_test_methods() -> dict[str, dict[str, bool]]:
    return _stem_methods("backend", BACKEND_TESTS.rglob("*.kt")) if BACKEND_TESTS.exists() else {}


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


WEB_TEST_RE = re.compile(r"\.(test|spec)\.(ts|tsx|js|jsx)$")


def _check_class_method(area: str, ref: str, stem_map: dict[str, dict[str, bool]], where: str) -> None:
    """Resolve a "ClassName.method" ref against that class's own methods (not a flat surface set)."""
    cls, sep, method = ref.rpartition(".")
    if not sep:  # no '.', no '/' -> malformed for these surfaces
        errors.append(f"{where}: {area} ref malformed (expected 'ClassName.method'): {ref}")
    elif cls not in stem_map:
        errors.append(f"{where}: {area} test class not found: {cls}")
    elif method not in stem_map[cls]:
        errors.append(f"{where}: {area} test method not found: {ref}")
    elif not stem_map[cls][method]:  # symbol resolves but its body is empty/comment-only
        errors.append(f"{where}: {area} test method has an empty body (proves nothing): {ref}")


def check_ref(area: str, ref: str, where: str) -> None:
    if area == "backend":
        _check_class_method(area, ref, backend_methods, where)
    elif area == "web":
        # web refs are file paths relative to web/, and must be test files (not any real file)
        if not (WEB / ref).exists():
            errors.append(f"{where}: web test file not found: {ref}")
        elif not WEB_TEST_RE.search(ref):
            errors.append(f"{where}: web ref is not a test file (expected *.test|*.spec .ts/.tsx/.js/.jsx): {ref}")


backend_methods = backend_test_methods()
usecases = sorted(FEATURES.glob("*/use-cases/uc.*.md"))

for uc in usecases:
    feature = uc.parent.parent.name
    stem = uc.stem
    cov = COVERAGE / feature / "use-cases" / f"{stem}.yaml"
    if not cov.exists():
        errors.append(f"{rel(uc)}: missing coverage file {rel(cov)}")
        continue
    try:
        data = yaml.safe_load(cov.read_text()) or {}
    except yaml.YAMLError as exc:
        errors.append(f"{rel(cov)}: invalid YAML: {exc}")
        continue
    where0 = rel(cov)
    if data.get("useCase") != stem:
        errors.append(f"{where0}: useCase '{data.get('useCase')}' must equal '{stem}'")
    if data.get("feature") != feature:
        errors.append(f"{where0}: feature '{data.get('feature')}' must equal '{feature}'")

    # A use-case may be refined (its doc + acceptance criteria written) before any surface implements
    # it. Such a file declares `status: planned` and carries no test refs yet — every surface it will
    # ever target must instead appear under `gaps` with a reason. Once a CR lands real tests, drop the
    # status and fill `coverage`. Omit `status` (or set anything but 'planned') = implemented, tests
    # required per criterion.
    status = data.get("status")
    if status not in (None, "planned"):
        errors.append(f"{where0}: unknown status '{status}' (omit for implemented, or 'planned')")
    planned = status == "planned"

    covered_areas: set[str] = set()  # areas that carry a coverage ref anywhere in this use-case
    for ac in data.get("acceptanceCriteria") or []:
        acid = ac.get("id", "?")
        where = f"{where0}: criterion '{acid}'"
        coverage = ac.get("coverage") or {}
        ref_count = 0
        for area, levels in coverage.items():
            if area not in SURFACES:
                errors.append(f"{where}: unknown area '{area}'")
            covered_areas.add(area)
            if not isinstance(levels, dict):
                errors.append(f"{where}: area '{area}' must map level -> [refs]")
                continue
            for level, refs in levels.items():
                if level not in KNOWN_LEVELS:
                    errors.append(f"{where}: unknown level '{level}' (area '{area}')")
                if not isinstance(refs, list) or not refs:
                    errors.append(f"{where}: area '{area}' level '{level}' has no refs")
                    continue
                for ref in refs:
                    ref_count += 1
                    check_ref(area, ref, where)
        if ref_count == 0 and not planned:
            errors.append(f"{where}: no tests (needs at least one on {sorted(SURFACES)})")

    gaps = data.get("gaps") or {}
    if planned and not gaps:
        errors.append(f"{where0}: status 'planned' must declare at least one gap (the surfaces not yet built)")
    for area, reason in gaps.items():
        if area not in SURFACES:
            errors.append(f"{where0}: gap for unknown area '{area}'")
        if not reason:
            errors.append(f"{where0}: gap for area '{area}' needs a reason")
        # A gap means "this surface has NO coverage ref for this use-case" (ADR 0040). An area that is
        # also covered is a stale gap — build-note or deferred-slice prose that no longer belongs here.
        if area in covered_areas:
            errors.append(f"{where0}: area '{area}' is both covered and a gap "
                          f"(a gap must be an uncovered surface; move build-notes/deferred prose out)")

# Orphan coverage files (coverage without a matching use-case).
for cov in COVERAGE.glob("*/use-cases/uc.*.yaml"):
    feature = cov.parent.parent.name
    uc = FEATURES / feature / "use-cases" / f"{cov.stem}.md"
    if not uc.exists():
        errors.append(f"{rel(cov)}: orphan coverage (no use-case {rel(uc)})")

if errors:
    print("COVERAGE VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"coverage OK: {len(usecases)} use-case(s) mapped, all acceptance criteria covered")
