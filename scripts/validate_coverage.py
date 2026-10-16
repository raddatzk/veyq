#!/usr/bin/env python3
"""Validate the feature/use-case -> coverage trust chain.

Checks that every use-case doc has a coverage file, that each is well-formed (every acceptance
criterion is covered by at least one test, keyed by area then level; uncovered surfaces are explicit
gaps), and that every referenced test actually exists, so dead refs fail early:
    backend -> "ClassName.method", resolved against *that* class (test file whose stem is ClassName
               must exist, declare method, AND that method must have a non-empty body — an empty `{}`
               block is rejected; a Kotlin expression body `= expr` counts, it delegates to real code)
               — and some CI run must actually execute it (below)
    web     -> a file path (relative to web/) that both exists AND is a test file (*.test|*.spec)
    crypto  -> the same rule, rooted at packages/crypto/ (the shared crypto workspace package, ADR 0065)
    admin-web -> the same rule, rooted at admin-web/ (the Server-Owner administration surface, ADR 0065)
    stack   -> the same rule, rooted at e2e/ (lane L1's browser suite against the candidate image, ADR 0090), where a
               Playwright setup file (*.setup.ts) is a test file too; which criterion its test carries is
               `validate_stack_criterion_tags.py`'s

`http` is NOT a coverage surface (ADR 0040): the `.http` files remain IntelliJ dev tooling but run in
no CI job, so they make no coverage claim — the same behaviour is proven by the backend slice tests.

Class-scoped resolution: a "ClassName.method" ref is checked against the test file whose stem is
ClassName, not a flat set of every method name on the surface — so a ref pointing at the wrong class
fails even if some other class happens to have a method of that (generic) name.

Reference *substance*, not just resolution: a ref that resolves can still point at nothing that runs —
a web ref at a non-test file (a real `package.json` once passed), a backend ref at an empty `fun` body.
Both degenerate cases are rejected here. This does NOT prove the test's assertion is *correct* — a body
that runs but asserts nothing (oracle correctness) stays ADR 0045's job (mutation score), never this
guard's. It also does NOT prove the cited test proves *this* criterion: a ref is bound to an AC by being
listed under it and by nothing else, so a sound, well-asserting test stays green beneath a sentence that
reaches past what it exercises — and a mutation score stays green too, because the test itself is fine.
The real case: a port-level test (a mailer returning its "cannot send" sentinel) cited under an AC that
claimed the *handler's* 503 refusal. Only reading the sentence against the test catches that.
The line is deliberate: "is it a test file / does it have a body" is decidable and cheap; "does the body
assert the right thing, and the right thing for *this* sentence" is not, and pretending otherwise would
oversell the green.

A backend ref must name a test some CI run EXECUTES (ADR 0040: a test no CI run executes makes no claim).
  What it asserts. The workflows under `.github/workflows/` are read for every *CI run of the backend's Gradle
  tests*: a step invoking `gradlew` with `test`/`check`/`build`, in a workflow triggered by `push` or
  `pull_request`, with no `if:` and no `continue-on-error` on its job or step (each of those means a green run
  does not say the test ran and passed). Each run carries the env the invocation sees (workflow ∪ job ∪ step
  `env:`, plus `export X=…` lines and `X=… ./gradlew` prefixes in that `run:`), the runner OS its `runs-on`
  names, and its `--tests` filters. A cited test counts iff at least one run selects it and satisfies every
  JUnit conditional-execution gate on the method or on any class enclosing it — including a gate reached
  through a composed annotation declared in the backend tests, or through an import alias. Decided gates:
  `@EnabledIfEnvironmentVariable` (the variable must be set to a value its `matches` regex fully matches) and
  `@EnabledOnOs`/`@DisabledOnOs` (against the runner OS — so the Windows-only exclusion on
  `TenantIngressUnwritablePathTest` is fine on today's Linux runners, and turns red the day the Gradle jobs
  move to a Windows runner). **Every other gate fails closed**: `@Disabled` never runs; `@EnabledIf`/
  `@DisabledIf` are code, undecidable here; `@…IfSystemProperty` reads the test JVM, which no workflow sets
  (the Gradle test task forwards no `-D`); `@DisabledIfEnvironmentVariable` depends on the runner's WHOLE
  environment, of which the workflow files are only part (`CI`, `GITHUB_*` are runner presets); JRE,
  architecture and native-image gates are simply not decided. Any `@Enabled…`/`@Disabled…` name counts as a
  gate, so a new one is red until someone decides it here. With no qualifying run at all, no backend ref makes
  a claim, and that is reported once. A workflow that does not parse as YAML is red, named — GitHub runs nothing
  from it, so it adds no run, and skipping it in silence would hide a run that stopped existing (inbox #239:
  `mutation.yml` once failed to load over an unquoted `: `, and this guard stepped around it without a word).
  The defect that bought it. `BlobBatchE2ETest.uploadThenDownloadRoundTripsBytes` — gated on `VEYQ_S3_E2E=1`,
  which no workflow sets — was cited under `uc.memory.gallery` / `download-original` until 6f19923 moved it to
  a gap. Every structural check passed: the file, the method and its body were all real. ADR 0040 Decision 3
  had said the rule "carries itself … checked by the build, not by a field"; the build skipped the test
  cleanly and said nothing. The web-e2e job's historical `if: false` is why a conditional job does not count.
  Its limit. It reads annotations and workflow files, not runtime behaviour: an `Assumptions.assume…` in a
  body or a `@BeforeAll` skips at runtime and still passes (pinned open in `validate_guard_gaps.py`); so do a
  custom `ExecutionCondition` behind `@ExtendWith`, a gate inherited from a superclass declared in another
  file, and a test-task `exclude`/`filter`/`excludeTags` in a backend build file (`backend/build.gradle.kts`, a
  module's, `buildSrc`'s `veyq.module`; none exists today). A
  variable a script exports, or one written to `$GITHUB_ENV`, is invisible — fail-closed, so such a ref is
  rejected until the variable moves into a workflow `env:`. Green means the workflows DECLARE a run that
  executes the test, never that one happened. It does not prove the step's `gradlew` is the backend's (only
  one Gradle build exists). And it covers backend refs only: web files gated behind
  `describe.skipIf(!process.env.VEYQ_E2E)` rely on `run_web_e2e.py` setting that variable, which
  `validate_e2e_discovery.py` guards from its side, not this one.

Coverage shape (per acceptance criterion):
    coverage:
      <area>:            # backend | web | crypto | admin-web
        <level>:         # unit | integration | e2e
          - <ref>        # see resolution rules above
      gaps:              # optional, per criterion: where this criterion's refs on a COVERED surface stop short
        - surface: <area>    # must carry a ref on this very criterion
          reason: <text>     # non-empty: the part of the sentence those refs do not prove, and why
    gaps:
      <area>: <reason>   # surfaces with NO coverage ref for this use-case (an area that IS covered
                         # may not appear here — overlap is a stale gap and fails, ADR 0040)
Keys are closed at both levels, so a misspelling (`gap:`) is an error rather than a silently dropped gap.
  The criterion-level list arrived with 635bb01 and 6f19923 and nothing read it — `surface: http` (the demoted
  surface) or an empty reason would have passed. It is the mirror of the use-case rule: a use-case gap names a
  surface with no ref; a criterion gap names a surface that HAS one on that criterion, so removing the last
  ref orphans the note and turns red. Its limit: whether the reason is true is prose, and stays unread.

Every use-case OWES `backend` and `web` (`OWED`): each is covered on some criterion or named under the use-case
`gaps` with a reason — never silent. `crypto`, `admin-web` and `stack` are opt-in: a ref or a gap on them is checked
like any other, and silence is legal (a use-case a lane L1 test is tagged with must still address `stack` —
`validate_stack_criterion_tags.py`).
  The defect that bought it. The coverage README and the comment on `SURFACES` both said a surface with no ref is
  "never left silent", and nothing read it. Measured 2026-09-23: six use-cases were silent on backend or web, and
  one silence hid a real surface — `uc.admin.create-tenant`'s invite acceptance lives in web (`TenantJoinView`),
  and its coverage file never said so. Read as written, the same sentence also owed `crypto` and `admin-web`,
  which 49 and 48 of the 52 use-cases never touch: enforcing that would have bought ~97 "not applicable" gaps
  that bury the few saying something.
  Its limit. It decides that an owed surface was *addressed*, never that the gap's reason is true (prose, unread).
  And it owes only the two surfaces every use-case crosses, so a Server-Owner use-case may stay silent on
  `admin-web`, where its client actually lives (pinned open in `validate_guard_gaps.py`); the `web` gap such a
  use-case must carry is where its author says where the client went.

A ref must sit on a surface a run has ADMITTED (`ADMITTED`; ADR 0040, the note on what a row is).
  What it asserts. A coverage row is a job, the runner that executes one surface's tests, and a declared run
  counts only on a job some run has already executed. A ref on a surface outside `ADMITTED` is rejected
  wherever it sits; the surface may still carry gaps, which is where its suites wait, named. `ADMITTED` is an
  allow-list, so a surface added to `SURFACES` starts unadmitted: gaps from day one, refs after its first run.
  The refs that predate the rule are named one by one in `UNADMITTED_BASELINE`, and an entry that matches no
  ref any more is itself an error, so that list only shrinks — including when its surface is admitted.
  The defect that bought it. ADR 0040 Decision 3 said the rule carries itself, and it did not. `admin-web` and
  `packages/crypto` were born on 2026-09-08, after the repository's last run; 14 admin-web refs
  (`uc.admin.create-tenant`, `uc.admin.suspend-tenant`) and 3 crypto refs went in green, while
  `uc.admin.setup-server` and `uc.admin.identify-instance-release` held their admin-web suites back "for the
  same reason the sibling admin use-cases are not" — a sibling claim that was false. Four files contradicted
  each other over one rule that nothing read (2026-09-24).
  Its limit. Admission is a hand edit. Whether a job ran is a fact about a run, not about a file, so the set
  cannot be derived from the tree, and the guard trusts whoever adds a surface to `ADMITTED` to have read a log
  showing that surface's suites executed. It also sees surfaces, not jobs: `web` is admitted as a whole, so a
  web suite moved into a second, never-run job would stay admitted.
"""
from __future__ import annotations

import fnmatch
import pathlib
import re
import shlex
import sys
from typing import NamedTuple

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

from kotlin_lexer import mask as _mask

try:
    import yaml
except ImportError:
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURES = ROOT / "docs" / "features"
COVERAGE = ROOT / "docs" / "coverage" / "features"
WEB = ROOT / "web"
CRYPTO = ROOT / "packages" / "crypto"
ADMIN_WEB = ROOT / "admin-web"
E2E = ROOT / "e2e"
WORKFLOWS = ROOT / ".github" / "workflows"

# The surface set, and the whole of it: every surface a CI job is written to run. Adding one permits gaps on it
# at once and refs only once a run has admitted it (ADMITTED), so a new surface is one line plus its resolution
# rule — owing it is a separate decision (OWED).
SURFACES = {"backend", "web", "crypto", "admin-web", "stack"}

# The surfaces every use-case OWES — covered, or under `gaps` with a reason, never silent: the two user-facing
# surfaces every use-case crosses. `crypto` is a library proven beneath web and `admin-web` the Server-Owner
# surface only admin use-cases touch, so owing them would demand a "not applicable" gap almost everywhere.
OWED = {"backend", "web"}

# The surfaces whose CI job some run has EXECUTED — the only ones whose refs make a claim (ADR 0040, the note on
# what a row is). An allow-list on purpose: a surface missing here is unadmitted, so forgetting to decide fails
# closed. Every run that admitted one predates the 2026-08-06 import into this repository, which has had no
# remote since. Admitting a surface is adding it here after reading a log that shows its suites executed —
# "the job passed" and "the job ran nothing" look the same until then.
ADMITTED = {"backend", "web"}

# Refs on an unadmitted surface written before the rule was enforced, named one by one as
# (use-case, criterion, surface, ref). Debt, not permission: an entry that matches no ref fails, so this only
# shrinks — moving a ref to its surface's gap, or admitting the surface, both demand deleting its entry here.
# It was born with three crypto refs, and all three moved to their use-cases' `crypto` gaps on 2026-09-24.
# Trust-device's went last: it was its criterion's only ref, and a gap alone would have made the use-case
# `planned`, which validate_capabilities.py reads as not built — until a web test pinned the same vector.
# Empty now, and kept so the rule reads the same: an entry added here would be new debt, not old.
UNADMITTED_BASELINE: set[tuple[str, str, str, str]] = set()

# Surfaces resolved as "a test-file path relative to this root". They differ only in the root they hang off,
# so the rule is a table rather than a branch per surface — adding the next one is one entry, not a branch.
FILE_ROOTS = {"web": WEB, "crypto": CRYPTO, "admin-web": ADMIN_WEB, "stack": E2E}
KNOWN_LEVELS = {"unit", "integration", "e2e"}
USECASE_KEYS = {"useCase", "feature", "title", "status", "acceptanceCriteria", "gaps"}
CRITERION_KEYS = {"id", "text", "coverage", "gaps"}

errors: list[str] = []


# ---- Kotlin: a lexer (not a parser) that makes comments and literals unable to pose as structure ----
# It lives in kotlin_lexer.py, shared with validate_kernel_imports_no_slices.


KOTLIN_FUN = r"fun\s+`?([A-Za-z0-9_]+)`?\s*\("


def _kotlin_funs(masked: str):
    """Yield (method-name, has_substance, offset of `fun`) for each `fun` declared in masked source.

    has_substance is False only for a **block body** that is empty or comment-only (`fun x() {}`).
    A Kotlin **expression body** (`fun x() = assertWriteGate(...)`) always counts — it delegates to
    real code, so treating it as "empty" would false-positive every delegating test (the edge that
    made naive `{}`-only detection wrong). Body-less abstract/expected decls yield False but are never
    ref targets, so it does not matter.

    Regex-with-brace-matching over `_mask`ed source, not a Kotlin parser: comments are already blank, so a
    comment-only body is an empty one; the param list is skipped by balanced-paren scan (so a default value's
    own `()` doesn't end it early); the body delimiter is the first `{` or `=` after the params, bounded by
    the enclosing `}` so a body-less decl can't borrow the next fun's body.
    """
    for m in re.finditer(KOTLIN_FUN, masked):
        name = m.group(1)
        i = m.end() - 1  # at the '(' of the param list
        depth = 0
        while i < len(masked):  # skip a balanced param list (default values may nest parens)
            if masked[i] == "(":
                depth += 1
            elif masked[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        j = i + 1  # first char past ')'
        while j < len(masked) and masked[j] not in "{=}":  # hop over a `: ReturnType` to the body delimiter
            j += 1
        if j >= len(masked) or masked[j] == "}":  # no body (abstract/expected) — not a ref target
            yield name, False, m.start()
        elif masked[j] == "=":  # expression body — delegates to real code, always substance
            yield name, True, m.start()
        else:  # '{' block body — substance iff anything but whitespace remains
            k = _brace_end(masked, j)
            yield name, masked[j + 1:k].strip() != "", m.start()


def _brace_end(masked: str, open_at: int) -> int:
    depth = 0
    for k in range(open_at, len(masked)):
        if masked[k] == "{":
            depth += 1
        elif masked[k] == "}":
            depth -= 1
            if depth == 0:
                return k
    return len(masked)


# ---- JUnit conditional-execution gates, and which declaration each one sits on ----

# `(?<![\w@.])` keeps labels (`return@forEach`, `this@Outer`) from reading as annotations.
_ANNOTATION = re.compile(r"(?<![\w@.])@(?:[a-z]+\s*:\s*)?([A-Za-z_][\w.]*)")
_WORD = re.compile(r"`[^`\n]*`|[A-Za-z_]\w*")
_DECL_KEYWORDS = {"class", "interface", "object", "fun", "val", "var", "typealias", "constructor", "init"}
_IMPORT_ALIAS = re.compile(r"^\s*import\s+[\w.]*?(\w+)\s+as\s+(\w+)", re.M)
# The name pattern IS the gate set: every JUnit condition is spelled @Enabled…/@Disabled…, so a new one is
# caught by name and then fails closed in _gate_blocks until someone decides it there.
_GATE_NAME = re.compile(r"(?:Enabled|Disabled)[A-Z]\w*|Disabled")
_KT_STRING = r'"((?:[^"\\\n]|\\.)*)"'
JUNIT_OS = ("LINUX", "MAC", "WINDOWS", "AIX", "FREEBSD", "OPENBSD", "SOLARIS", "OTHER")


class Gate(NamedTuple):
    name: str         # simple annotation name after import-alias resolution, e.g. EnabledIfEnvironmentVariable
    args: str         # raw argument text — the variable name and regex are read from here
    masked_args: str  # the same span masked — OS constants are read from here, so a reason string cannot vote
    at: str           # "File.kt:42", where the gate is written (the composed annotation's own line, if composed)


class Decl(NamedTuple):
    keyword: str      # class | interface | object | fun | val | …
    name: str         # "" when anonymous (`companion object`)
    at: int           # offset of the keyword
    modifiers: tuple  # e.g. ("annotation",) on an annotation class


class Fun(NamedTuple):
    substance: bool
    gates: tuple      # the gates on this fun and on every class enclosing it


class TestClass(NamedTuple):
    fqcn: str
    funs: dict        # method name -> [Fun, …] (overloads)


def _skip_args(masked: str, i: int) -> int:
    """If an argument list opens exactly at i, return the index past its balanced close; else i."""
    if i >= len(masked) or masked[i] != "(":
        return i
    depth = 0
    for k in range(i, len(masked)):
        if masked[k] == "(":
            depth += 1
        elif masked[k] == ")":
            depth -= 1
            if depth == 0:
                return k + 1
    return len(masked)


def _annotated_decl(masked: str, i: int) -> Decl | None:
    """From just past an annotation, walk over further annotations and modifiers to the declaration they all
    annotate; None when something other than a declaration follows (an annotated expression)."""
    modifiers = []
    while True:
        while i < len(masked) and masked[i].isspace():
            i += 1
        if i >= len(masked):
            return None
        if masked[i] == "@":
            a = _ANNOTATION.match(masked, i)
            if not a:
                return None
            i = _skip_args(masked, a.end())
            continue
        w = _WORD.match(masked, i)
        if not w:
            return None
        if w.group() in _DECL_KEYWORDS:
            k = w.end()
            while k < len(masked) and masked[k] in " \t":
                k += 1
            name = _WORD.match(masked, k)
            return Decl(w.group(), name.group().strip("`") if name else "", w.start(), tuple(modifiers))
        modifiers.append(w.group())
        i = w.end()


def _body_span(masked: str, decl: Decl) -> tuple[int, int]:
    """The `{…}` body of a class-like declaration; an empty span when it has none (`data class X(val a: Int)`)."""
    depth = 0
    stop = re.compile(r"[()\[\]{}]|\b(?:class|interface|object|fun|val|var|typealias)\b")
    for m in stop.finditer(masked, decl.at + len(decl.keyword)):
        t = m.group()
        if t in "([":
            depth += 1
        elif t in ")]":
            depth -= 1
        elif depth == 0:
            if t == "{":
                return m.start(), _brace_end(masked, m.start())
            return m.start(), m.start()  # a `}` or the next declaration came first: no body
    return len(masked), len(masked)


class _Scan(NamedTuple):
    path: pathlib.Path
    masked: str
    package: str
    annotations: list  # (resolved simple name, Gate | None, Decl | None)


def _scan(path: pathlib.Path) -> _Scan:
    src = path.read_text()
    masked = _mask(src)
    aliases = {alias: original for original, alias in _IMPORT_ALIAS.findall(masked)}
    pkg = re.search(r"^\s*package\s+([\w.]+)", masked, re.M)
    found = []
    for a in _ANNOTATION.finditer(masked):
        name = a.group(1).rstrip(".").rsplit(".", 1)[-1]
        name = aliases.get(name, name)
        end = _skip_args(masked, a.end())
        gate = None
        if _GATE_NAME.fullmatch(name):
            inner = slice(a.end() + 1, end - 1) if end > a.end() else slice(0, 0)
            gate = Gate(name, src[inner], masked[inner], f"{path.name}:{masked.count(chr(10), 0, a.start()) + 1}")
        found.append((name, gate, _annotated_decl(masked, end)))
    return _Scan(path, masked, pkg.group(1) if pkg else "", found)


def _composed_gates(scans) -> dict[str, tuple]:
    """Annotation classes declared in the backend tests that carry a gate (directly or through another
    composed annotation) -> those gates. JUnit honours such meta-annotations, so `@RequiresRealStore` hiding
    `@EnabledIfEnvironmentVariable` gates its test exactly as the bare annotation would."""
    gates: dict[str, list] = {}
    uses: dict[str, set] = {}
    for s in scans:
        for name, gate, decl in s.annotations:
            if decl and decl.keyword == "class" and "annotation" in decl.modifiers and decl.name:
                gates.setdefault(decl.name, [])
                uses.setdefault(decl.name, set()).add(name)
                if gate:
                    gates[decl.name].append(gate)
    changed = True
    while changed:  # a fixed point: composed-of-composed resolves however deep it nests
        changed = False
        for composed, used in uses.items():
            for g in [g for u in used for g in gates.get(u, ())]:
                if g not in gates[composed]:
                    gates[composed].append(g)
                    changed = True
    return {k: tuple(v) for k, v in gates.items() if v}


def _gated_funs(scan: _Scan, composed: dict[str, tuple]):
    """Yield (method name, Fun) for each fun in the file, with every gate that decides whether it runs."""
    file_wide: list = []
    on_fun: dict[int, list] = {}
    on_class: list = []  # ((body start, body end), gates)
    for name, gate, decl in scan.annotations:
        gates = (gate,) if gate else composed.get(name, ())
        if not gates:
            continue
        if decl is None:  # a gate this walk cannot attach — fail closed, it gates the whole file
            file_wide.extend(gates)
        elif decl.keyword == "fun":
            on_fun.setdefault(decl.at, []).extend(gates)
        elif decl.keyword in ("class", "interface", "object") and "annotation" not in decl.modifiers:
            on_class.append((_body_span(scan.masked, decl), gates))
    for name, has_substance, at in _kotlin_funs(scan.masked):
        enclosing = [g for (a, b), gs in on_class if a < at < b for g in gs]
        yield name, Fun(has_substance, tuple(file_wide + on_fun.get(at, []) + enclosing))


def _stem_classes(surface: str, files) -> dict[str, TestClass]:
    """Map each test file's stem -> TestClass(fqcn, {method name -> [Fun, …]}) for the funs it declares.

    Class-scoped ref resolution keys on the file stem, so it assumes stems are unique within a
    surface; two files sharing a stem would silently merge their methods and re-open the very
    cross-class hole this keying closes. That invariant holds today but isn't otherwise enforced,
    so collisions are reported as errors rather than assumed away.

    Per-file helper/stub/companion classes lump under the file stem; harmless because they are
    never ref targets, and stem-keying ignores them for free.
    """
    scans = [_scan(f) for f in files]
    composed = _composed_gates(scans)
    by_stem: dict[str, TestClass] = {}
    sources: dict[str, list[str]] = {}
    for s in scans:
        stem = s.path.stem
        cls = by_stem.setdefault(stem, TestClass(f"{s.package}.{stem}" if s.package else stem, {}))
        for name, fun in _gated_funs(s, composed):
            cls.funs.setdefault(name, []).append(fun)
        sources.setdefault(stem, []).append(rel(s.path))
    for stem, paths in sources.items():
        if len(paths) > 1:
            errors.append(f"{surface} test stem '{stem}' is not unique (breaks class-scoped "
                          f"resolution): {', '.join(sorted(paths))}")
    return by_stem


def backend_test_classes() -> dict[str, TestClass]:
    return _stem_classes("backend", backend_tree.test_kotlin())


# ---- CI: every run of the backend's Gradle tests, as the workflow files declare it ----

# Triggers that run on every change. `workflow_dispatch` alone is someone firing it by hand — the shape `http/`
# had — and `schedule` gates no change; neither makes a green build mean the test ran against it.
CI_TRIGGERS = {"push", "pull_request"}
GRADLE_TEST_TASKS = {"test", "check", "build"}
RUNNER_OS = {"ubuntu-": "LINUX", "macos-": "MAC", "windows-": "WINDOWS"}
_GRADLEW = re.compile(r"(?<![\w.-])(?:[\w.-]*/)*gradlew\b")
_SHELL_VALUE = r"""('[^']*'|"[^"]*"|[^\s;&|]*)"""


class CiRun(NamedTuple):
    where: str        # "ci.yml › backend"
    env: dict         # name -> value the Gradle invocation sees (None: a `${{ }}` expression, unknowable here)
    os: str | None    # the runner's JUnit OS constant; None when `runs-on` does not decide it
    tests: tuple      # `--tests` filters; empty = the whole suite


def _env_value(v) -> str | None:
    if isinstance(v, bool):  # YAML reads `true` as a boolean; the runner exports the string
        return "true" if v else "false"
    s = "" if v is None else str(v)
    return None if "${{" in s else s


def _env(mapping) -> dict:
    return {str(k): _env_value(v) for k, v in mapping.items()} if isinstance(mapping, dict) else {}


def _conditional(node: dict) -> bool:
    """A job or step that may not run (`if:`), or whose failure cannot redden the build (`continue-on-error`).
    Either way a green build does not say the test ran and passed — the web-e2e job once sat behind `if: false`."""
    return "if" in node or node.get("continue-on-error") not in (None, False)


def _gradle_test_runs(run: str):
    """Yield (inline env, --tests filters) for each `gradlew` invocation in a `run:` block that runs tests."""
    text = run.replace("\\\n", " ")
    for g in _GRADLEW.finditer(text):
        line_start = text.rfind("\n", 0, g.start()) + 1
        rest = re.split(r"&&|\|\||[;|\n]", text[g.end():], maxsplit=1)[0]
        try:
            tokens = shlex.split(rest)
        except ValueError:
            tokens = rest.split()
        tasks, excluded, tests = [], set(), []
        it = iter(tokens)
        for t in it:
            if t in ("-x", "--exclude-task"):
                excluded.add(next(it, "").rsplit(":", 1)[-1])
            elif t.startswith("--exclude-task="):
                excluded.add(t.split("=", 1)[1].rsplit(":", 1)[-1])
            elif t == "--tests":
                tests.append(next(it, ""))
            elif t.startswith("--tests="):
                tests.append(t.split("=", 1)[1])
            elif not t.startswith("-"):
                tasks.append(t.rsplit(":", 1)[-1])
        if excluded & {"test", "check"} or not GRADLE_TEST_TASKS & set(tasks):
            continue
        env = {}
        for e in re.finditer(r"^\s*export\s+([A-Za-z_]\w*)=" + _SHELL_VALUE, text[:line_start], re.M):
            env[e.group(1)] = e.group(2).strip("'\"")
        prefix = text[line_start:g.start()]  # `X=1 ./gradlew …` — only a pure assignment prefix reaches gradlew
        if re.fullmatch(r"\s*(?:env\s+)?(?:[A-Za-z_]\w*=" + _SHELL_VALUE + r"\s+)*", prefix):
            for e in re.finditer(r"([A-Za-z_]\w*)=" + _SHELL_VALUE, prefix):
                env[e.group(1)] = e.group(2).strip("'\"")
        yield env, tuple(tests)


def ci_backend_runs() -> list[CiRun]:
    runs = []
    for wf in sorted([*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")]):
        try:
            data = yaml.safe_load(wf.read_text()) or {}
        except yaml.YAMLError as exc:
            # A workflow GitHub cannot parse runs nothing — right for this question, so it adds no run. But it is
            # never skipped in silence: a broken workflow is red here, named, because nothing else in this guard
            # would notice that a run it was counting on has quietly stopped existing (inbox #239).
            errors.append(f"{rel(wf)}: does not parse as YAML ({str(exc).splitlines()[0]}), so GitHub runs "
                          f"nothing from it and no test run it declares can be counted — fix the file")
            continue
        on = data.get("on", data.get(True))  # YAML 1.1 reads a bare `on:` key as the boolean True
        triggers = {on} if isinstance(on, str) else set(on or ())
        if not triggers & CI_TRIGGERS:
            continue
        wf_env = _env(data.get("env"))
        for job_id, job in (data.get("jobs") or {}).items():
            if not isinstance(job, dict) or _conditional(job):
                continue
            runs_on = job.get("runs-on")
            runner_os = next((os_ for prefix, os_ in RUNNER_OS.items()
                              if isinstance(runs_on, str) and runs_on.startswith(prefix)), None)
            job_env = {**wf_env, **_env(job.get("env"))}
            for step in job.get("steps") or []:
                if not isinstance(step, dict) or _conditional(step) or not isinstance(step.get("run"), str):
                    continue
                step_env = {**job_env, **_env(step.get("env"))}
                for inline, tests in _gradle_test_runs(step["run"]):
                    runs.append(CiRun(f"{wf.name} › {job_id}", {**step_env, **inline}, runner_os, tests))
    return runs


def _env_gate_args(args: str) -> tuple[str | None, str | None]:
    def unescape(s: str) -> str:
        return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), s)

    named = re.search(r"\bnamed\s*=\s*" + _KT_STRING, args)
    matches = re.search(r"\bmatches\s*=\s*" + _KT_STRING, args)
    if named and matches:
        return unescape(named.group(1)), unescape(matches.group(1))
    positional = re.findall(_KT_STRING, args)  # declaration order: named, matches
    if "=" not in re.sub(_KT_STRING, "", args) and len(positional) >= 2:
        return unescape(positional[0]), unescape(positional[1])
    return None, None


def _gate_blocks(gate: Gate, run: CiRun) -> str | None:
    """Why `gate` keeps its test from running in `run`; None when it lets it run. Undecided gates block."""
    if gate.name == "EnabledIfEnvironmentVariable":
        named, pattern = _env_gate_args(gate.args)
        if named is None:
            return "its arguments are not string literals this guard can read"
        if named not in run.env:
            return f"{named} is not set"
        if run.env[named] is None:
            return f"{named} is set only to a `${{{{ }}}}` expression"
        try:
            ok = re.fullmatch(pattern, run.env[named]) is not None
        except re.error:
            return f"its pattern {pattern!r} is not a regex this guard can evaluate"
        return None if ok else f"{named}={run.env[named]!r} does not match {pattern!r}"
    if gate.name in ("EnabledOnOs", "DisabledOnOs"):
        listed = set(re.findall(r"\b(" + "|".join(JUNIT_OS) + r")\b", gate.masked_args))
        if not listed or "architectures" in gate.masked_args:
            return "its OS/architecture condition is not one this guard decides"
        if run.os is None:
            return "the runner OS is not decidable from `runs-on`"
        return None if (run.os in listed) == (gate.name == "EnabledOnOs") else f"the runner is {run.os}"
    if gate.name == "Disabled":
        return "it never runs"
    return "this guard does not decide that condition (fail-closed — decide it in _gate_blocks before citing)"


def _not_run_in_ci(cls: TestClass, method: str, gates: tuple) -> str | None:
    """None when some CI run executes this test; otherwise why none does."""
    simple = cls.fqcn.rsplit(".", 1)[-1]
    names = (cls.fqcn, f"{cls.fqcn}.{method}", simple, f"{simple}.{method}")
    runs = [r for r in CI_RUNS if not r.tests or any(fnmatch.fnmatchcase(n, p) for p in r.tests for n in names)]
    if not runs:
        return "no CI run's `--tests` filter selects it"
    why = []
    for r in runs:
        blocked = next(((g, b) for g in gates if (b := _gate_blocks(g, r))), None)
        if blocked is None:
            return None
        g, reason = blocked
        why.append(f"in {r.where}, @{g.name} ({g.at}): {reason}")
    return "; ".join(why)


def rel(p: pathlib.Path) -> str:
    return str(p.relative_to(ROOT))


WEB_TEST_RE = re.compile(r"\.(test|spec)\.(ts|tsx|js|jsx)$")
# Lane L1's Playwright projects: the scenarios (*.spec.ts) and the setup project's file (*.setup.ts), which is a test
# like any other there — first run is the scenario, not a fixture (ADR 0090 answer 4).
TEST_FILE_RE = {"stack": re.compile(r"\.(spec|setup)\.ts$")}


def _check_class_method(area: str, ref: str, classes: dict[str, TestClass], where: str) -> None:
    """Resolve a "ClassName.method" ref against that class's own methods (not a flat surface set)."""
    cls, sep, method = ref.rpartition(".")
    if not sep:  # no '.', no '/' -> malformed for these surfaces
        errors.append(f"{where}: {area} ref malformed (expected 'ClassName.method'): {ref}")
    elif cls not in classes:
        errors.append(f"{where}: {area} test class not found: {cls}")
    elif method not in classes[cls].funs:
        errors.append(f"{where}: {area} test method not found: {ref}")
    else:
        bodies = [f for f in classes[cls].funs[method] if f.substance]
        if not bodies:  # symbol resolves but its body is empty/comment-only
            errors.append(f"{where}: {area} test method has an empty body (proves nothing): {ref}")
        elif CI_RUNS:  # with none at all, main() reports it once rather than once per ref
            verdicts = [_not_run_in_ci(classes[cls], method, f.gates) for f in bodies]
            if all(verdicts):
                errors.append(f"{where}: {area} test runs in no CI run, so it makes no coverage claim "
                              f"(ADR 0040): {ref} — {verdicts[0]}")


backend_refs = 0


def check_ref(area: str, ref: str, where: str) -> None:
    global backend_refs
    if area == "backend":
        backend_refs += 1
        _check_class_method(area, ref, backend_classes, where)
    elif area in FILE_ROOTS:
        # these refs are file paths relative to the surface's own root, and must be test files (not any real file)
        root = FILE_ROOTS[area]
        if not (root / ref).exists():
            errors.append(f"{where}: {area} test file not found: {ref}")
        elif not TEST_FILE_RE.get(area, WEB_TEST_RE).search(ref):
            errors.append(f"{where}: {area} ref is not a test file "
                          f"(expected {TEST_FILE_RE.get(area, WEB_TEST_RE).pattern}): {ref}")


baseline_matched: set[tuple[str, str, str, str]] = set()


def check_admitted(use_case: str, criterion: str, area: str, ref: str, where: str) -> None:
    """A ref on a surface no run has admitted makes no claim, unless the baseline names it as old debt."""
    if area not in SURFACES or area in ADMITTED:
        return  # an unknown area is already reported; an admitted one is what every other check is for
    key = (use_case, criterion, area, ref)
    if key in UNADMITTED_BASELINE:
        baseline_matched.add(key)
        return
    errors.append(f"{where}: {area} ref {ref} makes no claim — no run has executed {area}'s CI job, so "
                  f"'{area}' is not in ADMITTED (ADR 0040, the note on what a row is). Name the suite under "
                  f"the use-case `gaps: {area}:` until a run's log shows it executed")


def check_criterion_gaps(cgaps, covered_here: set, where: str) -> None:
    """A criterion's `gaps` list: where its refs on a surface it DOES cover stop short of its sentence."""
    if not isinstance(cgaps, list) or not cgaps:
        errors.append(f"{where}: criterion gaps must be a non-empty list of {{surface, reason}}")
        return
    for g in cgaps:
        if not isinstance(g, dict) or set(g) != {"surface", "reason"}:
            errors.append(f"{where}: criterion gap must have exactly the keys surface, reason: {g!r}")
            continue
        surface, reason = g["surface"], g["reason"]
        if surface not in SURFACES:
            errors.append(f"{where}: criterion gap for unknown surface '{surface}'")
        elif surface not in covered_here:
            errors.append(f"{where}: criterion gap for '{surface}', which carries no ref on this criterion "
                          f"(a criterion gap says where a covered surface's refs stop short; a surface with no "
                          f"ref for the use-case is a use-case-level gap)")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(f"{where}: criterion gap for '{surface}' needs a reason")


backend_classes = backend_test_classes()
CI_RUNS = ci_backend_runs()
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
    if unknown := sorted(map(str, set(data) - USECASE_KEYS)):
        errors.append(f"{where0}: unknown key(s) {unknown} (expected {sorted(USECASE_KEYS)})")
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
        if unknown := sorted(map(str, set(ac) - CRITERION_KEYS)):
            errors.append(f"{where}: unknown key(s) {unknown} (expected {sorted(CRITERION_KEYS)})")
        coverage = ac.get("coverage") or {}
        ref_count = 0
        covered_here: set[str] = set()
        for area, levels in coverage.items():
            if area not in SURFACES:
                errors.append(f"{where}: unknown area '{area}'")
            covered_areas.add(area)
            covered_here.add(area)
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
                    check_admitted(stem, acid, area, ref, where)
        if ref_count == 0 and not planned:
            errors.append(f"{where}: no tests (needs at least one on {sorted(SURFACES)})")
        if "gaps" in ac:
            check_criterion_gaps(ac["gaps"], covered_here, where)

    gaps = data.get("gaps") or {}
    if not isinstance(gaps, dict):
        errors.append(f"{where0}: use-case gaps must map area -> reason "
                      f"(the list of {{surface, reason}} belongs on a criterion)")
        gaps = {}
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
    for area in sorted(OWED - covered_areas - set(gaps)):
        errors.append(f"{where0}: surface '{area}' is silent — no coverage ref and no gap (every use-case owes "
                      f"{sorted(OWED)}: cite a test, or name it under `gaps` with why it has none)")

if stray := sorted(ADMITTED - SURFACES):
    errors.append(f"{rel(pathlib.Path(__file__))}: ADMITTED names unknown surface(s) {stray} "
                  f"(expected a subset of {sorted(SURFACES)})")
for key in sorted(UNADMITTED_BASELINE - baseline_matched):
    errors.append(f"{rel(pathlib.Path(__file__))}: UNADMITTED_BASELINE entry {key} matches no ref on an "
                  f"unadmitted surface any more — delete it (the debt it recorded is paid, or its surface was "
                  f"admitted)")

if backend_refs and not CI_RUNS:
    errors.append(f"{rel(WORKFLOWS)}: no CI run executes the backend's Gradle tests (an unconditional "
                  f"`gradlew test|check|build` step in a push/pull_request workflow), so none of the "
                  f"{backend_refs} backend ref(s) makes a coverage claim (ADR 0040)")

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

print(f"coverage OK: {len(usecases)} use-case(s) mapped, all acceptance criteria covered; "
      f"{backend_refs} backend ref(s) run in CI ({', '.join(r.where for r in CI_RUNS) or 'no run'}); "
      f"awaiting a first run: {', '.join(sorted(SURFACES - ADMITTED)) or 'none'} "
      f"({len(baseline_matched)} baseline ref(s) owed)")
