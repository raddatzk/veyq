#!/usr/bin/env python3
"""Pin the documented gaps of the other guards, so the trust-chain skill's gap-map can't silently rot.

The other `validate_*.py` assert that a reference *resolves*, never that what it points at carries weight —
the `trust-chain` skill maps exactly where each stops. That map used to live as prose derived by reading guard
code (`SKILL.md`), which is a *remembered* claim wearing a command's clothes: tighten a validator to close a
gap and the prose goes silently false. That is the repo's recurring "Trap 5" (a remembered claim drifting from
reality), one level up — and it bought this guard.

So each documented gap becomes an **executable** assertion instead of a sentence. For every case below the
guard: copies the working tree into a throwaway dir (heavy dirs excluded), confirms the target validator is
**green on the un-forged copy** (else an unrelated red would masquerade as "the gap holds"), injects one
forgery, runs the real validator as a subprocess, and asserts its exit code is the documented one. The real
tree is never touched — every guard resolves `ROOT` as `__file__.parent.parent`, so running the *copied*
`scripts/<guard>.py` binds it to the copy; restoration is "delete the tempdir" (an abort leaves only an
orphaned tempdir, never a dirty tree — the automated evolution of `changes/README.md` § bite-proof).

The coupling is the point: if someone tightens a validator to close a gap, the documented pass/fail flips and
**this guard goes red** — forcing the closure to be recorded in `SKILL.md`, not landed silently. When that
happens the fix is *not* to relax this guard blindly: confirm the gap really closed, then move the skill's map
and this table together.

Scope — nine file-based cases: four the guard must **reject** (a closed hole, pinned so it can't silently
re-open) and five still-**open** gaps (the limit is documented, not yet closed). Two further documented gaps
stay out on purpose (below).

Rejected — regression-pins for closed holes (a forgery the guard must now catch):
  1  a web coverage ref at a *non-test* file — closed: a web ref must exist AND match *.test|*.spec, so a real
     `package.json` no longer resolves (validate_coverage `WEB_TEST_RE`)
  2  a backend ref at an *empty `fun` body* — closed: the resolved method's body is now read; an empty `{}`
     block is rejected, while a `= expr` expression body still counts (validate_coverage `_kotlin_funs`)
  4a a coverage `status: done` — rejected: the enum permits only unset/`planned`, so a loosened enum bites
  7  a base64 field's `maxLength` pushed above 65 532 (more than a `TEXT` column holds) on a non-allowlisted
     field — rejected: `validate_maxlength_storage` ties every base64 cap to its storage, so the #94 class
     (a cap larger than its column) can't silently re-enter
Open gaps — a forgery the guard still passes (a documented limit, not a defect to fix blindly):
  4b a lying `status: planned` on an implemented use-case passes. **Left open on purpose** — the obvious rule
     "planned ⇒ no coverage refs" is *false*: `uc.spaces.verify-identity` is legitimately `planned` yet carries
     a real ref (`identityPins.test.ts`), a partially-built use-case. The narrow decidable rule ("every criterion
     covered ⇒ planned is a contradiction") shuts only the *full* evasion, never the partial ("cover 5 of 6, mark
     planned, skip the 6th"), so it is much mechanism for one theoretical lie. This counter-example is the whole
     reason 4b stays open — recorded here, beside the case, so nobody re-derives the false rule from scratch.
  5  a CR marked `status: done` with no implementation passes (validate_changes: enum + refs, nothing more)
  6  a link to a non-existent `#fragment` anchor in an existing file passes (validate_doc_links strips it)
  8  a *free-text* (non-base64) `maxLength` blown past what its column holds passes — `validate_maxlength_storage`
     reasons only about base64 caps (char == byte), never free-text byte-width (`chars × up-to-4` under utf8mb4)
     nor columns that serialize several capped fields (`terms_markdown_by_locale`). **Left open on purpose** —
     catching it needs the field→column map the guard deliberately does not carry (a manifest that rots); the
     one real free-text-on-`TEXT` column was instead *widened to MEDIUMTEXT* in #94, closing that instance by
     construction. The gap is the class, not that column.
  9  rate-limit state in a limiter named neither `*Limiter*` nor `*Counter*` passes `validate_ratelimit_core`,
     which matches on the **filename** (ADR 0052). **Left open on purpose** — the reach of a buildless grep *is*
     the naming convention, and no grep can enforce a name that has not been written yet; closing it needs the
     reflection sweep the posture guard already pays for, which would leave this glob entirely. Pinned so the
     limit stated in that guard's docstring is executable rather than a sentence.
  10 a mutated policy that gains a dependency on a file no `paths:` filter lists passes
     `validate_mutation_trigger_paths` — it derives one path per manifest entry and cannot follow imports, so
     editing that dependency alone moves a mutation score without triggering the gate. **Left open on purpose**
     — a path filter is a *file* trigger; following imports would mean a Kotlin dependency graph in a buildless
     Python guard, and the honest alternative (widen the filter to the four scope directories) costs ~70 files'
     worth of no-op mutation runs against the 10 that are in scope. Pinned so the limit in that guard's
     docstring is executable rather than a sentence.
Out of reach by construction, not deferred work: the mutation **total-vs-per-glob** asymmetry (needs a report
fixture + git, so it belongs to a mutation-job-local test, not this buildless glob) and the **rate-limit posture
sweep** (ADR 0051). The sweep is a JVM test — it enumerates JAX-RS endpoints by reflection over compiled classes
and a live CDI container — so this guard, which forges *files* and runs a *Python* validator as a subprocess,
has no way to inject into it. Its boundary (green proves every endpoint **declares** a posture, never that a
domain-keyed limiter **fires**) is therefore held where it can be: in the sweep's own docstring, in the
`trust-chain` skill's gap-map, and in `docs/crosscutting/cc.security.rate-limit.md`. (The other historical deferral here —
CR-number reuse against history, old inbox #107 — is *gone*, not deferred: CRs are named by slug, not an
allocated number, so there is no number to reuse and nothing to guard. See `changes/README.md` § naming.)

A `validate_*.py`, so the trust-chain glob runs it on every push with no build. Runnable locally:

    python3 scripts/validate_guard_gaps.py
"""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Heavy or irrelevant to the pure-file validators; skipping them turns a multi-GB copy into ~10 MB / <1 s.
# `.git` matters twice: it is huge, and its absence is fine — none of the six target guards reads history.
_IGNORE = shutil.ignore_patterns(
    ".git", "node_modules", "build", ".gradle", ".venv", "dist", "target", "__pycache__",
)

# Anchors the forgeries edit. If one drifts (a coverage file relocated, a test class renamed), the forgery
# fails to apply and the case reports red with a "could not forge" message — deliberately loud, because a
# forgery that silently no-ops would make the assertion vacuously pass.
COV_APPEARANCE = "docs/coverage/features/settings/use-cases/uc.settings.appearance-language.yaml"
COV_SHARE_NOTE = "docs/coverage/features/sharing/use-cases/uc.sharing.share-note.yaml"
WEB_TEST_REF = "src/shared/state/useThemeState.test.ts"
BACKEND_TEST_CLASS = "ShareNoteSliceTest"
BACKEND_TEST_REF = f"{BACKEND_TEST_CLASS}.createWithNoteThenAnonymousResolveReturnsIt"
# A distinctive base64 cap (the space icon, 21 884) — forging it oversize is the #94 closed-hole probe; a
# free-text cap (the operator terms, 20 000) — forging it huge is the free-text open-gap probe.
B64_CAP_ANCHOR = ("contracts/api/scopes.openapi.yaml", "maxLength: 21884", "maxLength: 65536")
FREETEXT_CAP_ANCHOR = ("contracts/api/_common.openapi.yaml", "maxLength: 20000", "maxLength: 999996")
# The volume that carries the config-secret KEK across a container recreate — the hole ADR 0056 § Consequences
# surfaced and cr-instance-backup-and-restore closed. Removing it is the whole forgery.
PROD_COMPOSE = "infra/docker-compose.prod.yml"
CONFIG_SECRET_MOUNT = "      - veyq-config-secret:/var/lib/veyq\n"


def _sub(text: str, old: str, new: str) -> str:
    """Replace exactly once, asserting `old` was present — a drifted anchor becomes a loud ForgeError."""
    if old not in text:
        raise ForgeError(f"anchor not found (drifted?): {old!r}")
    return text.replace(old, new, 1)


class ForgeError(Exception):
    """A forgery could not be applied — its anchor drifted. Update this guard's anchors."""


# ---- forgeries: each mutates the copied tree in place so the target guard sees exactly one planted gap ----

def forge_web_ref_nontest(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), f"[{WEB_TEST_REF}]", "[package.json]"))
    return "web coverage ref → package.json (a real, non-test file)"


def forge_backend_empty_fun(root: pathlib.Path) -> str:
    tests = [t for t in (root / "backend/src/test").rglob("*.kt") if t.stem == BACKEND_TEST_CLASS]
    if not tests:
        raise ForgeError(f"backend test class {BACKEND_TEST_CLASS} not found (renamed?)")
    tf = tests[0]
    src = tf.read_text()
    cut = src.rstrip().rfind("}")  # inject before the class's final brace
    if cut == -1:
        raise ForgeError(f"no closing brace in {tf.name}")
    tf.write_text(src[:cut] + "\n    fun guardGapEmptyProof() {}\n" + src[cut:])
    cov = root / COV_SHARE_NOTE
    cov.write_text(_sub(cov.read_text(), BACKEND_TEST_REF, f"{BACKEND_TEST_CLASS}.guardGapEmptyProof"))
    return "backend ref → empty fun body (symbol resolves; the body is now inspected)"


def forge_coverage_status_done(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), "useCase: uc.settings.appearance-language",
                      "useCase: uc.settings.appearance-language\nstatus: done"))
    return "coverage status: done (must be REJECTED — a loosened status enum would bite)"


def forge_lying_planned(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    t = _sub(f.read_text(), "useCase: uc.settings.appearance-language",
             "useCase: uc.settings.appearance-language\nstatus: planned")
    t = t.rstrip() + '\ngaps:\n  backend: "not built yet (a lie: web is fully implemented)"\n'
    f.write_text(t)
    return "lying status: planned on an implemented use-case (+ a gap on the untargeted surface)"


def forge_cr_done_no_impl(root: pathlib.Path) -> str:
    # A valid slug-named CR the guard will accept (id == filename stem); content is a valid-but-empty 'done' CR.
    (root / "changes/cr-guard-gap-phantom.md").write_text(textwrap.dedent("""\
        ---
        id: cr-guard-gap-phantom
        title: phantom done CR (guard-gap probe)
        status: done
        satisfies: []
        adrs: []
        invariants: []
        ---
        ## Goal
        A CR marked done whose implementation never existed. validate_changes cannot tell.
        """))
    return "CR status: done with no implementation (enum + refs checked, never the code)"


def forge_doc_dead_fragment(root: pathlib.Path) -> str:
    (root / "docs/guard-gap-probe.md").write_text(
        "# Probe\n\nSee [the readme](../changes/README.md#this-anchor-does-not-exist-zzz).\n")
    return "doc link to a non-existent #fragment anchor (fragment stripped, never verified)"


def forge_b64_cap_over_text(root: pathlib.Path) -> str:
    rel, old, new = B64_CAP_ANCHOR
    f = root / rel
    f.write_text(_sub(f.read_text(), old, new))
    return f"base64 cap {old} → {new} on a non-allowlisted field (must be REJECTED — the #94 class)"


def forge_freetext_cap_over_column(root: pathlib.Path) -> str:
    rel, old, new = FREETEXT_CAP_ANCHOR
    f = root / rel
    f.write_text(_sub(f.read_text(), old, new))
    return f"free-text cap {old} → {new} (guard reasons only about base64 — the open gap)"


def forge_ratelimit_state_under_another_name(root: pathlib.Path) -> str:
    # validate_ratelimit_core matches on the FILENAME (*Limiter*/*Counter*), so a limiter that avoids both name
    # parts holds its own map unseen. This is the whole reach of a buildless grep — pinned, not fixed: enforcing a
    # name nobody has written yet is not decidable, and the alternative (a reflection sweep) leaves the glob.
    (root / "backend/src/main/kotlin/com/veyq/app/features/sharing/ShareThrottle.kt").write_text(textwrap.dedent("""\
        package com.veyq.app.features.sharing

        import java.util.concurrent.ConcurrentHashMap

        /** A guard-gap probe: rate-limit state under a name the filename grep does not match. */
        class ShareThrottle {
            private val windows = ConcurrentHashMap<String, Long>()
            fun allow(shareId: String): Boolean = windows.putIfAbsent(shareId, 1L) == null
        }
        """))
    return "rate-limit state in a limiter named neither *Limiter* nor *Counter* (the filename-grep open gap)"


def forge_scoped_policy_gains_unlisted_dependency(root: pathlib.Path) -> str:
    # validate_mutation_trigger_paths derives ONE path per manifest entry, so it sees a scoped class's own file
    # and nothing it depends on. Plant exactly that: a scoped policy now imports a helper whose file is in no
    # `paths:` filter, so editing the helper alone moves the mutation score without triggering the gate. Not
    # fixable by this guard's mechanism — a path filter cannot follow imports — so it is pinned, not closed. If
    # someone ever teaches the guard to walk dependencies, this case flips red instead of landing silently.
    helper = root / "backend/src/main/kotlin/com/veyq/app/shared/membership/PolicyHelper.kt"
    helper.write_text(textwrap.dedent("""\
        package com.veyq.app.shared.membership

        /** A guard-gap probe: a dependency of a mutated policy that no `paths:` filter lists. */
        object PolicyHelper {
            fun isLastOwner(owners: Int): Boolean = owners <= 1
        }
        """))
    policy = root / "backend/src/main/kotlin/com/veyq/app/features/spaces/scope/MembershipPolicy.kt"
    src = policy.read_text()
    if "import com.veyq.app.shared.membership.MembershipEntity" not in src:
        raise ForgeError("MembershipPolicy's MembershipEntity import drifted — re-anchor this forgery")
    policy.write_text(_sub(src, "import com.veyq.app.shared.membership.MembershipEntity",
                           "import com.veyq.app.shared.membership.MembershipEntity\n"
                           "import com.veyq.app.shared.membership.PolicyHelper"))
    return "a mutated policy depending on a file in no `paths:` filter (the transitive-dependency open gap)"


def forge_unmounted_config_secret(root: pathlib.Path) -> str:
    f = root / PROD_COMPOSE
    if not f.is_file():
        raise ForgeError(f"{PROD_COMPOSE} is missing — the production deployment artifact moved or was deleted")
    f.write_text(_sub(f.read_text(), CONFIG_SECRET_MOUNT, ""))
    return "the app's config-secret volume removed (the KEK would not survive a container recreate)"


# id, target guard, forgery, expect_pass (True = guard passes the forgery = gap still open; False = must reject)
CASES = [
    ("1",  "validate_coverage.py",  forge_web_ref_nontest,      False),
    ("2",  "validate_coverage.py",  forge_backend_empty_fun,    False),
    ("4a", "validate_coverage.py",  forge_coverage_status_done, False),
    ("4b", "validate_coverage.py",  forge_lying_planned,        True),
    ("5",  "validate_changes.py",   forge_cr_done_no_impl,      True),
    ("6",  "validate_doc_links.py", forge_doc_dead_fragment,    True),
    ("7",  "validate_maxlength_storage.py", forge_b64_cap_over_text,          False),
    ("8",  "validate_maxlength_storage.py", forge_freetext_cap_over_column,   True),
    ("9",  "validate_ratelimit_core.py",    forge_ratelimit_state_under_another_name, True),
    ("10", "validate_mutation_trigger_paths.py", forge_scoped_policy_gains_unlisted_dependency, True),
    ("11", "validate_config_secret_persistence.py", forge_unmounted_config_secret, False),
]


def run_guard(root: pathlib.Path, script: str) -> int:
    return subprocess.run(
        [sys.executable, str(root / "scripts" / script)],
        capture_output=True, text=True,
    ).returncode


def copy_tree() -> pathlib.Path:
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardgap_"))
    shutil.copytree(ROOT, tmp / "repo", ignore=_IGNORE, symlinks=True)
    return tmp


def check_case(case_id: str, script: str, forge, expect_pass: bool) -> str | None:
    """Return an error string if the documented pass/fail no longer holds, else None."""
    tmp = copy_tree()
    try:
        root = tmp / "repo"
        if run_guard(root, script) != 0:
            return (f"case {case_id}: {script} is already RED on the un-forged copy — cannot trust the "
                    f"forgery result (fix the underlying red first)")
        try:
            planted = forge(root)
        except ForgeError as e:
            return f"case {case_id}: could not apply forgery — {e}. Update this guard's anchors."
        passed = run_guard(root, script) == 0
        if passed != expect_pass:
            want = "still pass (gap open)" if expect_pass else "reject it"
            now = "passed" if passed else "was rejected"
            return (f"case {case_id}: {script} should {want} for [{planted}], but it {now}. "
                    f"If a validator was deliberately tightened, move the SKILL.md gap-map and this "
                    f"guard's CASES table together — do not just relax this check.")
        return None
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    errors = [e for c in CASES if (e := check_case(*c))]
    if errors:
        print("GUARD-GAP VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"guard gaps OK: {len(CASES)} pinned case(s) hold (closed holes stay rejected, open gaps stay open)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
