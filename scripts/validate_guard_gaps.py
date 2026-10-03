#!/usr/bin/env python3
"""Pin the documented gaps of the other guards, so the trust-chain skill's gap-map can't silently rot.

The other `validate_*.py` assert that a reference *resolves*, never that what it points at carries weight —
the `trust-chain` skill maps exactly where each stops. That map used to live as prose derived by reading guard
code (`SKILL.md`), which is a *remembered* claim wearing a command's clothes: tighten a validator to close a
gap and the prose goes silently false. That is the repo's recurring "Trap 5" (a remembered claim drifting from
reality), one level up — and it bought this guard.

So each documented gap becomes an **executable** assertion instead of a sentence. The guard snapshots the
source tree once, confirms each target validator is **green on an un-forged copy** of it (else an unrelated red
would masquerade as "the gap holds"), and then for every case below: copies the snapshot into a throwaway dir,
injects one forgery, runs the real validator as a subprocess, and asserts its exit code is the documented one.
The *source tree* is what git lists — every tracked file plus every untracked one it does not ignore — so
dependencies, build output, run artefacts and nested worktrees stay out by the repository's own `.gitignore`,
not by a list kept here. A name list (`node_modules`, `build`, …) was what this used to copy by, and it let
through everything it did not name: in a main checkout carrying `.claude/worktrees/`, `.e2e-out/` and
`graphify-out/` each copy was ~9.6k files where the sources are ~1.3k, and each of the per-case copies took
15–20 s (inbox #244). It also keeps a validator that walks the filesystem on the tree CI sees, not on a
sibling worktree's sources. The real
tree is never touched — every guard resolves `ROOT` as `__file__.parent.parent`, so running the *copied*
`scripts/<guard>.py` binds it to the copy; restoration is "delete the tempdir" (an abort leaves only an
orphaned tempdir, never a dirty tree — the automated evolution of `changes/README.md` § bite-proof).

The coupling is the point: if someone tightens a validator to close a gap, the documented pass/fail flips and
**this guard goes red** — forcing the closure to be recorded in `SKILL.md`, not landed silently. When that
happens the fix is *not* to relax this guard blindly: confirm the gap really closed, then move the skill's map
and this table together.

Scope — file-based cases only, each either one the guard must **reject** (a closed hole, pinned so it can't
silently re-open) or a still-**open** gap (the limit is documented, not yet closed). `CASES` below is the
list and the only place the tally lives: a count restated here rots, and did — it said "nine" while eleven
ran. Two further documented gaps stay out on purpose (below).

Rejected — regression-pins for closed holes (a forgery the guard must now catch):
  1  a web coverage ref at a *non-test* file — closed: a web ref must exist AND match *.test|*.spec, so a real
     `package.json` no longer resolves (validate_coverage `WEB_TEST_RE`)
  2  a backend ref at an *empty `fun` body* — closed: the resolved method's body is now read; an empty `{}`
     block is rejected, while a `= expr` expression body still counts (validate_coverage `_kotlin_funs`)
  4a a coverage `status: done` — rejected: the enum permits only unset/`planned`, so a loosened enum bites
  7  a base64 field's `maxLength` pushed above 65 532 (more than a `TEXT` column holds) on a non-allowlisted
     field — rejected: `validate_maxlength_storage` ties every base64 cap to its storage, so the #94 class
     (a cap larger than its column) can't silently re-enter
  11 the app's config-secret volume removed from the production compose file — rejected:
     `validate_config_secret_persistence` pins the mount, so the KEK cannot silently stop surviving a
     container recreate (the hole ADR 0056 § Consequences surfaced)
  12 a fourth column referencing `veyq_blob`, declared in Kotlin and in a migration with its own foreign key —
     rejected: `validate_blob_reference_arity` pins the three arms by name, so an arm added without teaching
     the predicates that enumerate them cannot land quietly. It is the one case here whose cost is *user
     data*: a reclaim predicate short by one arm deletes live content, and the arm it would miss
     (`veyq_node.cover_blob_id`) is the one with no foreign key to refuse
  13 a scheduled purge deleting store objects inline, under a receiver named something other than `blobStore`
     and typed with a fully-qualified name — rejected: `validate_store_reclaim_arity` keys on the declared
     type, not on one spelling. The shape is chosen because it is the one that got through: that guard's first
     predicate matched only an unqualified `: BlobStore` and reported **green** over a tree carrying this exact
     bypass. What a bypass costs is the whole of ADR 0062 — a delete with no failure channel, at a point where
     the row naming the key is already gone
  14 one of the routability-changing tenant operations stops calling the ingress projector — rejected:
     `validate_tenant_ingress_triggers` derives the routability-changing `TenantStore` methods from the store
     itself and requires each `TenantHandler` caller to reproject, so ADR 0063's "never an incremental patch"
     cannot decay into "patched by whoever remembered". The defect is silent by construction: the row stays
     right, the routing goes stale, and the first symptom is a domain that does not resolve
  17 a cited backend test class gated by `@EnabledIfEnvironmentVariable` on a variable no workflow sets —
     rejected: `validate_coverage` reads the gate and requires a CI run of the Gradle tests that satisfies it
     (ADR 0040: a test no CI run executes makes no claim). The real instance was `BlobBatchE2ETest`, cited under
     `uc.memory.gallery` until 6f19923 with every structural check green
  18 every job that runs the backend's Gradle tests made conditional (`if: false`) — rejected: with no
     unconditional run left, no backend ref makes a claim, and the guard says so once. `if: false` is the exact
     shape the web-e2e job carried while four coverage refs rode on it
  19 a criterion-level gap naming `http`, the surface ADR 0040 demoted — rejected: criterion `gaps` lists are now
     read (known surface, non-empty reason, and a surface that carries a ref on that criterion). Before, nothing
     read them at all
  20 a backend ref at a method that exists only inside a KDoc example — rejected: `validate_coverage` scans
     comment- and string-masked source. Unmasked, `TestMailbox`'s KDoc made `TestMailbox.x` a resolvable method
     with a body
  22 a use-case silent on an owed surface (`uc.settings.appearance-language` with its backend gap removed, so
     backend has neither a ref nor a gap) — rejected: `validate_coverage` requires every use-case to address
     `OWED` (backend, web). The coverage README and the `SURFACES` comment had said "never left silent" with
     nothing reading it; six files were silent (2026-09-23), and one silence hid a real web surface
  24 an `admin-web` ref on an implemented use-case (a real admin-web test file, under a criterion that is
     otherwise sound) — rejected: `validate_coverage` admits refs only on a surface in `ADMITTED`, one whose CI
     job some run has executed (ADR 0040, the note on what a row is). ADR 0040 said that rule carries itself;
     14 admin-web and 3 crypto refs went in green before anything read it (2026-09-24)
  26 `BlobUploadRequestItem.wrappedBlobKey` loses its `@Size`, its `maxLength` left standing — rejected:
     `validate_request_bounds` requires every request-body bound to carry its annotation. Before it, nothing tied
     the two, and `BlobUploadsBatchRequest.items` carried `maxItems: 256` with no `@Size` at all (inbox #153)
  27 `BlobUploadsBatchRequest.items` loses its `@field:jakarta.validation.Valid` — rejected: validation does not
     descend without it, so every item-level annotation below is dead, which is exactly how the `wrappedBlobKey`
     checks sat until #153
  28 `MoveMediaRequest.mediaIds`' `@Size` says one more than its `maxItems` — rejected: the two are one rule
     written twice, value for value, and an annotation drifting from its schema is the same gap as a missing one
  29 a `web/src` comment citing a use-case id under a real feature that names no doc — rejected:
     `validate_doc_citations` resolves `uc.`/`en.`/`cc.` ids, not only `ADR NNNN`. Before it, 7 such ids dangled
     at 12 sites across the client, the backend and a spec, and two agents followed them into nothing (inbox #122)
  31 a German wiki page declaring one doc fewer than its English pair — rejected: `validate_wiki_sources` holds a
     pair to the same `speaks-for` set, so a change to one doc cannot reach one language's page and not the other
  32 a declared doc's every link back to its wiki page removed — rejected: `validate_wiki_sources` requires each
     `docs/` source to link the page, so whoever edits the doc sees the published page that restates it. Before
     it, a page told users the opposite of its doc and was found because a user asked (inbox #120)
Open gaps — a forgery the guard still passes (a documented limit, not a defect to fix blindly):
  4b a lying `status: planned` on an implemented use-case passes. **Left open on purpose** — the obvious rule
     "planned ⇒ no coverage refs" is *false in general*: a use-case can be legitimately `planned` while carrying
     a real ref on a criterion that already shipped ahead of the rest (a partially-built use-case). The narrow
     decidable rule ("every criterion covered ⇒ planned is a contradiction") shuts only the *full* evasion, never
     the partial ("cover 5 of 6, mark planned, skip the 6th"), so it is much mechanism for one theoretical lie.
     `uc.spaces.verify-identity` used to be the live instance of exactly this shape (inbox #128) — split
     2026-08-25 into a fully-built file and a fully-`planned` one, so the repo no longer has a concrete example,
     but the abstract gap in the rule is unchanged and a future file can reopen it. Recorded here, beside the
     case, so nobody re-derives the false rule from scratch.
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
  15 a `$PROJECT_DIR$`-rooted path inside a run configuration's `<env>`/`<entry>` value pointing nowhere passes
     `validate_run_configs`, which resolves a configuration's *subject* (the `package.json` plus the script it
     names, the Gradle project, the shell working directory) and never an env value. **Left open on purpose** —
     a build output legitimately does not exist on a clean tree (an env value naming a file the backend it
     configures writes under `backend/build/`), so resolving env paths would make that guard red on arrival,
     and red-because-wrong is indistinguishable from red-because-broken for every later reader. The same limit
     covers the value that actually bites — a
     `VEYQ_BLOB_STORE_PUBLIC_ENDPOINT` disagreeing with the origin you browse is green here and a broken
     presigned URL in the browser — which no file-shaped assertion can decide at all. Pinned so the limit in
     that guard's docstring is executable rather than a sentence.
  16 a CSS class that no markup applies passes `validate_stylesheet_anchors` when it is not the selector's
     ANCHOR — `.a-live-class .never-applied` is green, `.never-applied` alone is red. **Left open on purpose**
     — the inside of a selector is exactly where a stylesheet legitimately reaches into a dependency's DOM
     (`.memory-card .p-card-body`), and the trust-chain job is buildless, so there is no `node_modules` and
     therefore no honest way to tell a vendor class from a typo at that position. Anchoring is what lets that
     guard need no vendor list at all. Pinned so the limit in that guard's docstring is executable rather than
     a sentence.
  21 a cited backend test that skips itself at runtime (`Assumptions.assumeTrue(...)` in its body) passes
     `validate_coverage`, which decides annotation gates, never runtime behaviour. **Left open on purpose** —
     an assumption is an arbitrary expression, possibly in a `@BeforeAll` or a helper it calls, so a check
     would catch only the direct spelling: much mechanism for part of the lie, the same trade as 4b. Pinned so
     the limit in that guard's docstring is executable rather than a sentence.
  23 a Server-Owner use-case silent on `admin-web` (`uc.admin.setup-server` with its admin-web gap removed)
     passes `validate_coverage`, which owes only backend and web. **Left open on purpose** — owing `admin-web`
     everywhere would demand a "not applicable" gap on nearly every file, and owing it per feature fits no
     feature (admin mixes web, admin-web and backend-only use-cases) without a declaration the coverage schema
     does not carry, the schema growth ADR 0040 declined. The `web` gap such a file must carry is where it says
     where its client went. Pinned so the limit in that guard's docstring is executable rather than a sentence.
  25 a Host header read through an alias constant (`const val` holding the header name, then
     `getHeaderString(<the constant>)`) in a resource passes `validate_lookup_single_home`, whose spellings are
     literals at the call. **Left open on purpose** — following a constant to its use is data flow, which a
     buildless grep does not have, and the realistic regression (a hand parse copied from an old resource) is a
     literal and is caught. Pinned so the limit in that guard's docstring is executable rather than a sentence.
  30 a `web/src` comment citing a use-case id under a feature directory that does not exist passes
     `validate_doc_citations`, which reads an id only under a real feature directory. **Left open on purpose** —
     that anchor is what keeps attribute chains (`.parent.parent`, `.value.trim()`) from reading as ids; without
     it the guard cries wolf on Python and TypeScript alike. Pinned so tightening the anchor flips it visibly.
  33 a wiki page whose prose contradicts the doc it declares passes `validate_wiki_sources`, which checks the
     address and never the claim. **Left open on purpose** — whether two sentences in two registers say the same
     thing is not decidable by a grep; the forgery is the sentence that bought that guard, put back. Pinned so
     the limit in that guard's docstring is executable rather than a sentence.
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
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Anchors the forgeries edit. If one drifts (a coverage file relocated, a test class renamed), the forgery
# fails to apply and the case reports red with a "could not forge" message — deliberately loud, because a
# forgery that silently no-ops would make the assertion vacuously pass.
COV_APPEARANCE = "docs/coverage/features/settings/use-cases/uc.settings.appearance-language.yaml"
COV_SHARE_NOTE = "docs/coverage/features/sharing/use-cases/uc.sharing.share-note.yaml"
WEB_TEST_REF = "src/shared/state/useThemeState.test.ts"
BACKEND_TEST_CLASS = "ShareNoteSliceTest"
BACKEND_TEST_METHOD = "createWithNoteThenAnonymousResolveReturnsIt"
BACKEND_TEST_REF = f"{BACKEND_TEST_CLASS}.{BACKEND_TEST_METHOD}"
# A criterion-level gap (a covered surface's refs stopping short) — its surface is what case 19 forges.
COV_GALLERY = "docs/coverage/features/memory/use-cases/uc.memory.gallery.yaml"
CRITERION_GAP_SURFACE = "      - surface: backend\n"
# The use-case gap that keeps an owed surface from being silent (case 22 removes it; 4b leans on it), and a
# Server-Owner use-case's admin-web gap (case 23 removes it, leaving the web gap after it in place).
OWED_GAP_ANCHOR = "\ngaps:\n  backend: >-\n"
COV_SETUP = "docs/coverage/features/admin/use-cases/uc.admin.setup-server.yaml"
ADMIN_WEB_GAP_ANCHOR = "\ngaps:\n  admin-web: >-\n"
NEXT_GAP_ANCHOR = "\n  web: >-\n"
# A real admin-web test file — case 24 cites it on a surface no run has admitted, so only admission can reject it.
ADMIN_WEB_TEST_REF = "src/cli/cli.test.ts"
# A distinctive base64 cap (the space icon, 21 884) — forging it oversize is the #94 closed-hole probe; a
# free-text cap (the operator terms, 20 000) — forging it huge is the free-text open-gap probe.
B64_CAP_ANCHOR = ("contracts/api/scopes.openapi.yaml", "maxLength: 21884", "maxLength: 65536")
FREETEXT_CAP_ANCHOR = ("contracts/api/_common.openapi.yaml", "maxLength: 20000", "maxLength: 999996")
# The volume that carries the config-secret KEK across a container recreate — the hole ADR 0056 § Consequences
# surfaced and cr-instance-backup-and-restore closed. Removing it is the whole forgery.
PROD_COMPOSE = "infra/docker-compose.prod.yml"
CONFIG_SECRET_MOUNT = "      - veyq-config-secret:/var/lib/veyq\n"
# Where a fourth veyq_blob reference arm is forged: a scope is the plausible place for one (a space banner),
# and it is one of the entities the delete paths already touch.
SCOPE_ENTITY = "backend/src/main/kotlin/com/veyq/app/shared/scope/ScopeEntity.kt"
SCOPE_ENTITY_ANCHOR = "class ScopeEntity : PanacheEntityBase {\n"
BANNER_MIGRATION = "backend/src/main/resources/db/migration/V99__guard_gap_scope_banner.sql"
# Where a bypass of the single-deleter rule is forged. A scheduled purge is the plausible place: it already
# reclaims rows, so deleting the objects inline is the one-line shortcut ADR 0062 Decision 3 forbids.
TRASH_PURGE = "backend/src/main/kotlin/com/veyq/app/features/media/MediaTrashPurge.kt"
TENANT_HANDLER = "backend/src/main/kotlin/com/veyq/app/features/admin/tenant/TenantHandler.kt"
TENANT_REPROJECT_CALL = "        if (outcome is SuspendOutcome.Done) reproject()\n"
TRASH_PURGE_CTOR_ANCHOR = "    private val store: MediaStore,\n"
# Where a Host read through an alias constant is forged: the recovery resource is where the hand parse that
# bought validate_lookup_single_home used to live, so it is where a copy of it would plausibly come back.
RECOVERY_RESOURCE = "backend/src/main/kotlin/com/veyq/app/features/account/recovery/RecoveryResource.kt"
RECOVERY_RESOURCE_ANCHOR = "class RecoveryResource("
TRASH_PURGE_BODY_ANCHOR = "        store.purgeTrashedBefore(Instant.now().minus(retention))\n"


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
    tf = _backend_test_file(root)
    src = tf.read_text()
    cut = src.rstrip().rfind("}")  # inject before the class's final brace
    if cut == -1:
        raise ForgeError(f"no closing brace in {tf.name}")
    tf.write_text(src[:cut] + "\n    fun guardGapEmptyProof() {}\n" + src[cut:])
    cov = root / COV_SHARE_NOTE
    cov.write_text(_sub(cov.read_text(), BACKEND_TEST_REF, f"{BACKEND_TEST_CLASS}.guardGapEmptyProof"))
    return "backend ref → empty fun body (symbol resolves; the body is now inspected)"


def _backend_test_file(root: pathlib.Path) -> pathlib.Path:
    tests = [t for t in (root / "backend/src/test").rglob("*.kt") if t.stem == BACKEND_TEST_CLASS]
    if not tests:
        raise ForgeError(f"backend test class {BACKEND_TEST_CLASS} not found (renamed?)")
    return tests[0]


def forge_backend_ref_env_gated(root: pathlib.Path) -> str:
    # The BlobBatchE2ETest shape, planted on the class the coverage file already cites so the forgery needs no
    # second anchor: a class-level gate on a variable no workflow sets, beneath the class's other annotations.
    tf = _backend_test_file(root)
    src = _sub(tf.read_text(), "import org.junit.jupiter.api.Test\n",
               "import org.junit.jupiter.api.Test\nimport org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable\n")
    tf.write_text(_sub(src, f"\nclass {BACKEND_TEST_CLASS} ",
                       f'\n@EnabledIfEnvironmentVariable(named = "VEYQ_GUARD_GAP_PROBE", matches = "1")'
                       f"\nclass {BACKEND_TEST_CLASS} "))
    return "a cited backend class gated on an env variable no workflow sets (must be REJECTED — it never runs in CI)"


def forge_backend_tests_never_run_in_ci(root: pathlib.Path) -> str:
    # Every job whose steps run the backend's Gradle tests gets `if: false` — the web-e2e job's historical shape.
    # Text-level on purpose: a forgery must not borrow the guard's own predicate, or it could only ever agree.
    hits = 0
    for wf in sorted((root / ".github/workflows").glob("*.yml")):
        text = wf.read_text()
        jobs = re.search(r"^jobs:\n", text, re.M)
        if not jobs:
            continue
        parts = re.split(r"(?m)^(?=  [\w-]+:\s*$)", text[jobs.end():])
        for i, part in enumerate(parts):
            if re.search(r"gradlew[^\n]*\b(?:check|test|build)\b", part):
                header, _, rest = part.partition("\n")
                parts[i] = f"{header}\n    if: false\n{rest}"
                hits += 1
        wf.write_text(text[:jobs.end()] + "".join(parts))
    if not hits:
        raise ForgeError("no workflow job runs `gradlew check|test|build` — re-anchor this forgery")
    return f"all {hits} job(s) running the backend's Gradle tests set to `if: false` (must be REJECTED)"


def forge_criterion_gap_demoted_surface(root: pathlib.Path) -> str:
    f = root / COV_GALLERY
    f.write_text(_sub(f.read_text(), CRITERION_GAP_SURFACE, "      - surface: http\n"))
    return "a criterion-level gap naming `http`, the demoted surface (must be REJECTED — the list is now read)"


def forge_backend_ref_kdoc_phantom(root: pathlib.Path) -> str:
    tf = _backend_test_file(root)
    src = tf.read_text()
    cut = src.rstrip().rfind("}")  # inject before the class's final brace
    if cut == -1:
        raise ForgeError(f"no closing brace in {tf.name}")
    tf.write_text(src[:cut] + "\n    /** An example, not a method: `fun guardGapKdocPhantom() = check(true)` */\n"
                  + src[cut:])
    cov = root / COV_SHARE_NOTE
    cov.write_text(_sub(cov.read_text(), BACKEND_TEST_REF, f"{BACKEND_TEST_CLASS}.guardGapKdocPhantom"))
    return "backend ref → a `fun` that exists only inside a KDoc (must be REJECTED — comments are masked)"


def forge_backend_runtime_assumption(root: pathlib.Path) -> str:
    tf = _backend_test_file(root)
    head = f"    fun {BACKEND_TEST_METHOD}() {{\n"
    tf.write_text(_sub(tf.read_text(), head, head + '        org.junit.jupiter.api.Assumptions.assumeTrue('
                                                      'System.getenv("VEYQ_GUARD_GAP_PROBE") == "1")\n'))
    return "a cited backend test that skips itself at runtime via Assumptions (the annotation-only open gap)"


def forge_coverage_status_done(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), "useCase: uc.settings.appearance-language",
                      "useCase: uc.settings.appearance-language\nstatus: done"))
    return "coverage status: done (must be REJECTED — a loosened status enum would bite)"


def forge_lying_planned(root: pathlib.Path) -> str:
    # `planned` needs a gap; the file's own backend gap is that gap, so the lie is the status line alone.
    f = root / COV_APPEARANCE
    t = f.read_text()
    if OWED_GAP_ANCHOR not in t:
        raise ForgeError(f"anchor not found (drifted?): {OWED_GAP_ANCHOR!r}")
    f.write_text(_sub(t, "useCase: uc.settings.appearance-language",
                      "useCase: uc.settings.appearance-language\nstatus: planned"))
    return "lying status: planned on an implemented use-case (its real backend gap satisfies planned-needs-a-gap)"


def forge_owed_surface_silent(root: pathlib.Path) -> str:
    f = root / COV_APPEARANCE
    t = f.read_text()
    cut = t.find(OWED_GAP_ANCHOR)
    if cut == -1:
        raise ForgeError(f"anchor not found (drifted?): {OWED_GAP_ANCHOR!r}")
    f.write_text(t[:cut + 1])  # the backend gap ends the file, so it goes with everything after it
    return "a use-case silent on backend, an owed surface — no ref, no gap (must be REJECTED)"


def forge_admin_use_case_silent_on_admin_web(root: pathlib.Path) -> str:
    f = root / COV_SETUP
    t = f.read_text()
    start = t.find(ADMIN_WEB_GAP_ANCHOR)
    end = t.find(NEXT_GAP_ANCHOR, start + 1) if start != -1 else -1
    if end == -1:
        raise ForgeError(f"anchors not found (drifted?): {ADMIN_WEB_GAP_ANCHOR!r} … {NEXT_GAP_ANCHOR!r}")
    f.write_text(t[:start] + "\ngaps:" + t[end:])
    return "a Server-Owner use-case silent on admin-web, an opt-in surface (the owes-only-backend-and-web limit)"


def forge_unadmitted_surface_ref(root: pathlib.Path) -> str:
    # The ref resolves and is a test file, and admin-web is neither a gap nor owed here, so every other check
    # passes: the one thing left to reject it is that no run has executed admin-web's job.
    f = root / COV_APPEARANCE
    f.write_text(_sub(f.read_text(), f"[{WEB_TEST_REF}]",
                      f"[{WEB_TEST_REF}] }}\n      admin-web: {{ unit: [{ADMIN_WEB_TEST_REF}]"))
    if not (root / "admin-web" / ADMIN_WEB_TEST_REF).exists():
        raise ForgeError(f"admin-web test file missing (moved?): {ADMIN_WEB_TEST_REF}")
    return "an admin-web ref on an implemented use-case — a surface no run has admitted (must be REJECTED)"


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


def _in_request_property(root: pathlib.Path, spec: str, schema: str, prop: str, old: str, new: str) -> None:
    """Replace `old` once inside one property's block of one component schema — anchored on the names, so a
    reworded neighbour cannot move the forgery onto another field."""
    f = root / "contracts" / "api" / spec
    lines = f.read_text().split("\n")
    try:
        start = lines.index(f"    {schema}:")
        begin = next(i for i in range(start + 1, len(lines)) if lines[i] == f"        {prop}:")
    except (ValueError, StopIteration):
        raise ForgeError(f"{spec}: {schema}.{prop} not found (renamed?)")
    end = next((j for j in range(begin + 1, len(lines)) if re.match(r"^ {0,8}\S", lines[j])), len(lines))
    block = "\n".join(lines[begin:end])
    lines[begin:end] = _sub(block, old, new).split("\n")
    f.write_text("\n".join(lines))


def forge_request_bound_unannotated(root: pathlib.Path) -> str:
    _in_request_property(root, "media.openapi.yaml", "BlobUploadRequestItem", "wrappedBlobKey",
                         " @field:jakarta.validation.constraints.Size(max = 256)", "")
    return "wrappedBlobKey's @Size removed, its maxLength kept (must be REJECTED — the #153 class)"


def forge_request_cascade_dropped(root: pathlib.Path) -> str:
    _in_request_property(root, "media.openapi.yaml", "BlobUploadsBatchRequest", "items",
                         " @field:jakarta.validation.Valid", "")
    return "the @Valid on BlobUploadsBatchRequest.items removed (must be REJECTED — every item check dead)"


def forge_request_annotation_drifts(root: pathlib.Path) -> str:
    _in_request_property(root, "media.openapi.yaml", "MoveMediaRequest", "mediaIds", "max = 256)", "max = 257)")
    return "MoveMediaRequest.mediaIds annotated max 257 over maxItems 256 (must be REJECTED)"


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


def forge_fourth_blob_reference_arm(root: pathlib.Path) -> str:
    # A fourth place referencing veyq_blob, declared the way a real one would be: a Kotlin @Column plus the
    # migration that adds the column and its foreign key. It is deliberately spelled the way the NAIVE locators
    # miss — the column name is not the annotation's first argument, and `references` is lowercase — because
    # that pair reports an unchanged 3 and 2 on exactly this forgery, which is the reason the arity guard
    # exists at all. Two files, so the entity key and the schema/backstop keys each see it independently.
    entity = root / SCOPE_ENTITY
    src = entity.read_text()
    entity.write_text(_sub(src, SCOPE_ENTITY_ANCHOR, SCOPE_ENTITY_ANCHOR + textwrap.dedent("""\

            /** A guard-gap probe: a fourth arm referencing veyq_blob. */
            @Column(length = 36, name="banner_blob_id")
            var bannerBlobId: String? = null
        """)))
    (root / BANNER_MIGRATION).write_text(textwrap.dedent("""\
        -- A guard-gap probe: the SQL half of a fourth arm referencing veyq_blob.
        ALTER TABLE veyq_scope ADD COLUMN banner_blob_id VARCHAR(36) NULL;
        ALTER TABLE veyq_scope ADD CONSTRAINT fk_scope_banner_blob
            FOREIGN KEY (banner_blob_id) references veyq_blob (id);
        """))
    return "a fourth column referencing veyq_blob, in Kotlin and SQL (must be REJECTED — consumers are now short an arm)"


def forge_direct_store_delete_bypass(root: pathlib.Path) -> str:
    # A purge that deletes store objects inline instead of recording them for the sweep. Spelled the way the
    # NAIVE locator misses, and this one is not hypothetical: the receiver is named something other than
    # `blobStore` AND its type is fully qualified, which is exactly the shape that ran GREEN against this
    # guard's first predicate over a tree carrying the bypass. Pinning that shape is the point of the case —
    # the easy `grep 'blobStore.delete'` reports nothing here.
    f = root / TRASH_PURGE
    if not f.is_file():
        raise ForgeError(f"{TRASH_PURGE} is missing — the media trash purge moved or was deleted")
    src = _sub(
        f.read_text(),
        TRASH_PURGE_CTOR_ANCHOR,
        TRASH_PURGE_CTOR_ANCHOR + "    private val objects: com.veyq.app.shared.blobstore.BlobStore,\n",
    )
    f.write_text(_sub(src, TRASH_PURGE_BODY_ANCHOR, TRASH_PURGE_BODY_ANCHOR + '        objects.delete(listOf("k/0"))\n'))
    return "a purge deleting store objects inline under a renamed, fully-qualified receiver (must be REJECTED)"


def forge_unmounted_config_secret(root: pathlib.Path) -> str:
    f = root / PROD_COMPOSE
    if not f.is_file():
        raise ForgeError(f"{PROD_COMPOSE} is missing — the production deployment artifact moved or was deleted")
    f.write_text(_sub(f.read_text(), CONFIG_SECRET_MOUNT, ""))
    return "the app's config-secret volume removed (the KEK would not survive a container recreate)"


def forge_tenant_suspend_without_reproject(root: pathlib.Path) -> str:
    # One of the five routability-changing tenant operations stops re-projecting the reverse proxy's routing.
    # This is the shape the defect actually takes: nothing else changes, the tenant row is still correct, and
    # neither the compiler nor any type notices — a suspended tenant simply keeps resolving. The `suspend` arm is
    # chosen over `create` because its damage points the wrong way (a tenant taken offline that is still
    # reachable), and because it is the one an author is most likely to think of as "only a status flip".
    f = root / TENANT_HANDLER
    if not f.is_file():
        raise ForgeError(f"{TENANT_HANDLER} is missing — tenant administration moved or was deleted")
    src = f.read_text()
    if TENANT_REPROJECT_CALL not in src:
        raise ForgeError("the suspend arm's reproject() call drifted — re-anchor this forgery")
    f.write_text(_sub(src, TENANT_REPROJECT_CALL, ""))
    return "a tenant suspend that never re-projects the routing (must be REJECTED — the domain keeps resolving)"


def forge_run_config_env_path_into_the_void(root: pathlib.Path) -> str:
    # validate_run_configs resolves a configuration's SUBJECT and never an env value, so add an env value naming
    # a path that does not exist and it still reports green. The forgery brings its own entry rather than bending
    # an existing one: env values are developer-tunable lines, and since the dev-invite file left (inbox #285)
    # none of them is a `$PROJECT_DIR$` path at all — pinning one's spelling would turn an innocent edit red.
    env_map = re.compile(r"(<envs>|<map>)")
    for f in sorted((root / ".run").glob("*.xml")):
        src = f.read_text()
        if env_map.search(src):
            probe = '<entry key="VEYQ_GUARD_GAP_PROBE" value="$PROJECT_DIR$/does-not-exist/guard-gap-probe.jsonl" />'
            if "<envs>" in src:
                probe = probe.replace("<entry key=", "<env name=")
            f.write_text(env_map.sub(lambda m: m.group(1) + probe, src, count=1))
            return ("a run configuration's env value pointing at a path that does not exist "
                    "(the env-values-unresolved open gap)")
    raise ForgeError("no .run/*.xml carries an env map — re-anchor this forgery")


def forge_host_read_through_an_alias(root: pathlib.Path) -> str:
    # validate_lookup_single_home matches the header name as a literal AT the call (or anywhere, for the forwarded
    # header). Hold the plain header's name in a constant and read through it, and no spelling matches: the call
    # carries no literal and the literal sits in no call. The name is assembled here, not written, so this file
    # does not carry a copy of the shape it forges.
    f = root / RECOVERY_RESOURCE
    if not f.is_file():
        raise ForgeError(f"{RECOVERY_RESOURCE} is missing — the recovery resource moved or was deleted")
    header = "Ho" + "st"
    probe = (
        f'private const val HOST_ALIAS = "{header}"\n\n'
        "/** A guard-gap probe: a Host read through an alias constant. */\n"
        "private fun aliasedHost(req: io.vertx.core.http.HttpServerRequest): String? = req.getHeader(HOST_ALIAS)\n\n"
    )
    f.write_text(_sub(f.read_text(), RECOVERY_RESOURCE_ANCHOR, probe + RECOVERY_RESOURCE_ANCHOR))
    return "a Host header read through an alias constant (the data-flow open gap)"


def forge_dead_class_inside_a_selector(root: pathlib.Path) -> str:
    # validate_stylesheet_anchors judges a selector's ANCHOR compound and nothing deeper, so a class no markup
    # applies is green wherever it sits behind a combinator. The live anchor is read out of the sheet rather
    # than spelled here: pinning one class name would turn a rename into a red guard, which is the opposite of
    # what a gap-pin is for.
    sheet = root / "web" / "src" / "styles" / "ui.css"
    if not sheet.is_file():
        raise ForgeError("web/src/styles/ui.css is missing — the shared primitives sheet moved or was deleted")
    src = sheet.read_text()
    m = re.search(r"^\.([a-z][a-z0-9-]*)\s*\{", src, re.M)
    if not m:
        raise ForgeError("no class-anchored rule in ui.css — re-anchor this forgery")
    sheet.write_text(src + "\n.%s .guard-gap-never-applied {\n  color: red;\n}\n" % m.group(1))
    return ("a class no markup applies, behind a combinator rather than at the anchor "
            "(the anchor-only open gap)")


def _web_comment_citing(root: pathlib.Path, feature: str) -> None:
    # A new file rather than an edit: the probe needs no anchor in existing source, so it cannot drift. The id is
    # assembled, not written, so this guard's own source carries no dangling id for anyone to copy.
    probe = root / "web" / "src" / "guardGapProbe.ts"
    probe.write_text("// " + ".".join(["uc", feature, "guard-gap-no-such-doc"]) + " (web) — a guard-gap probe\n"
                     "export const guardGapProbe = 1\n")


def forge_feature_id_dangles(root: pathlib.Path) -> str:
    if not (root / "docs" / "features" / "memory").is_dir():
        raise ForgeError("docs/features/memory is gone — re-anchor this forgery on a live feature directory")
    _web_comment_citing(root, "memory")
    return "a web/src comment citing a use-case id under a real feature that names no doc"


def forge_feature_id_under_unknown_feature(root: pathlib.Path) -> str:
    feature = "guard-gap-no-such-feature"
    if (root / "docs" / "features" / feature).exists():
        raise ForgeError(f"docs/features/{feature} exists — pick another non-feature name")
    _web_comment_citing(root, feature)
    return "a web/src comment citing a use-case id under a feature that does not exist (the anchor open gap)"


def _wiki_header(page: pathlib.Path) -> tuple[list[str], str]:
    m = re.match(r"<!--\s*speaks-for:\s*([^;]*?)\s*;\s*pair:\s*(.*?)\s*-->", page.read_text())
    if not m:
        raise ForgeError(f"{page.name} has no speaks-for header — re-anchor this forgery")
    return [s.strip() for s in m.group(1).split(",")], m.group(2)


def forge_wiki_pair_declares_fewer(root: pathlib.Path) -> str:
    # The page is read out of the tree, not named: any German page whose header declares two docs or more.
    for page in sorted((root / "wiki" / "de").glob("*.md")):
        sources, _pair = _wiki_header(page)
        if len(sources) >= 2:
            text = page.read_text()
            page.write_text(_sub(text, ", " + sources[-1], ""))
            return f"wiki/de/{page.name} declares one doc fewer than its English pair"
    raise ForgeError("no wiki/de page declares two docs or more — re-anchor this forgery")


def forge_wiki_backlink_removed(root: pathlib.Path) -> str:
    # Every markdown link from the first declared docs/ source back to its English page is unlinked (kept as
    # text): one link left standing would keep the guard green, which is a bite that never landed.
    for page in sorted((root / "wiki" / "en").glob("*.md")):
        for s in _wiki_header(page)[0]:
            doc = root / s
            if not (s.startswith("docs/") and doc.is_file()):
                continue
            link = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")

            def unlink(m: re.Match) -> str:
                target = m.group(2).split("#", 1)[0]
                return m.group(1) if target and (doc.parent / target).resolve() == page.resolve() else m.group(0)

            text = doc.read_text()
            stripped = link.sub(unlink, text)
            if stripped == text:
                raise ForgeError(f"{s} carries no link to wiki/en/{page.name} to remove")
            doc.write_text(stripped)
            return f"{s} no longer links back to wiki/en/{page.name}"
    raise ForgeError("no wiki/en page declares a docs/ source — re-anchor this forgery")


def forge_wiki_prose_contradicts_doc(root: pathlib.Path) -> str:
    page = root / "wiki" / "en" / "account-keys-and-recovery.md"
    if not page.is_file():
        raise ForgeError("wiki/en/account-keys-and-recovery.md is gone — re-anchor this forgery")
    page.write_text(page.read_text() + "\nTo log in, your password is sent to the server.\n")
    return "a wiki page claiming the password is sent to the server, against the key model it declares"


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
    ("12", "validate_blob_reference_arity.py", forge_fourth_blob_reference_arm, False),
    ("13", "validate_store_reclaim_arity.py", forge_direct_store_delete_bypass, False),
    ("14", "validate_tenant_ingress_triggers.py", forge_tenant_suspend_without_reproject, False),
    ("15", "validate_run_configs.py", forge_run_config_env_path_into_the_void, True),
    ("16", "validate_stylesheet_anchors.py", forge_dead_class_inside_a_selector, True),
    ("17", "validate_coverage.py", forge_backend_ref_env_gated,          False),
    ("18", "validate_coverage.py", forge_backend_tests_never_run_in_ci,  False),
    ("19", "validate_coverage.py", forge_criterion_gap_demoted_surface,  False),
    ("20", "validate_coverage.py", forge_backend_ref_kdoc_phantom,       False),
    ("21", "validate_coverage.py", forge_backend_runtime_assumption,     True),
    ("22", "validate_coverage.py", forge_owed_surface_silent,            False),
    ("23", "validate_coverage.py", forge_admin_use_case_silent_on_admin_web, True),
    ("24", "validate_coverage.py", forge_unadmitted_surface_ref,         False),
    ("25", "validate_lookup_single_home.py", forge_host_read_through_an_alias, True),
    ("26", "validate_request_bounds.py", forge_request_bound_unannotated, False),
    ("27", "validate_request_bounds.py", forge_request_cascade_dropped,   False),
    ("28", "validate_request_bounds.py", forge_request_annotation_drifts, False),
    ("29", "validate_doc_citations.py", forge_feature_id_dangles,            False),
    ("30", "validate_doc_citations.py", forge_feature_id_under_unknown_feature, True),
    ("31", "validate_wiki_sources.py", forge_wiki_pair_declares_fewer,     False),
    ("32", "validate_wiki_sources.py", forge_wiki_backlink_removed,        False),
    ("33", "validate_wiki_sources.py", forge_wiki_prose_contradicts_doc,   True),
]


def run_guard(root: pathlib.Path, script: str) -> int:
    return subprocess.run(
        [sys.executable, str(root / "scripts" / script)],
        capture_output=True, text=True,
    ).returncode


def source_files() -> list[str]:
    """Every file git tracks, plus every untracked one it does not ignore — the tree the validators are about.

    `.git` is not among them, and need not be: no target guard reads history. A directory entry (git lists an
    untracked nested repository or worktree as `path/`) is not a file and is skipped, as is a tracked file
    deleted in the working tree.
    """
    out = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                         cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit(f"GUARD-GAP VALIDATION FAILED: `git ls-files` failed, so there is no tree to copy and "
                         f"no case was checked: {out.stderr.strip()}")
    files = sorted({p for p in out.stdout.split("\0") if p and ((ROOT / p).is_file() or (ROOT / p).is_symlink())})
    if not files:
        raise SystemExit("GUARD-GAP VALIDATION FAILED: `git ls-files` listed no file, so no case was checked")
    return files


def snapshot() -> pathlib.Path:
    """Copy the source tree once; every case copies this, so all of them see the same tree."""
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardgap_snapshot_"))
    made: set[pathlib.Path] = set()
    for rel in source_files():
        dst = tmp / rel
        if dst.parent not in made:
            dst.parent.mkdir(parents=True, exist_ok=True)
            made.add(dst.parent)
        shutil.copy2(ROOT / rel, dst, follow_symlinks=False)
    return tmp


def copy_tree(snap: pathlib.Path) -> pathlib.Path:
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="guardgap_"))
    shutil.copytree(snap, tmp / "repo", symlinks=True)
    return tmp


def green_unforged(snap: pathlib.Path, script: str, verdicts: dict[str, bool]) -> bool:
    """Is `script` green on an un-forged copy? Asked once per guard: every copy of one snapshot is the same tree."""
    if script not in verdicts:
        tmp = copy_tree(snap)
        try:
            verdicts[script] = run_guard(tmp / "repo", script) == 0
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return verdicts[script]


def check_case(snap: pathlib.Path, verdicts: dict[str, bool],
               case_id: str, script: str, forge, expect_pass: bool) -> str | None:
    """Return an error string if the documented pass/fail no longer holds, else None."""
    if not green_unforged(snap, script, verdicts):
        return (f"case {case_id}: {script} is already RED on the un-forged copy — cannot trust the "
                f"forgery result (fix the underlying red first)")
    tmp = copy_tree(snap)
    try:
        root = tmp / "repo"
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
    snap = snapshot()
    try:
        verdicts: dict[str, bool] = {}
        errors = [e for c in CASES if (e := check_case(snap, verdicts, *c))]
    finally:
        shutil.rmtree(snap, ignore_errors=True)
    if errors:
        print("GUARD-GAP VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(f"guard gaps OK: {len(CASES)} pinned case(s) hold (closed holes stay rejected, open gaps stay open)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
