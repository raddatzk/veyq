---
name: trust-chain
description: >-
  Use before asserting any doc, coverage, contract, or CR change is sound; when adding or modifying a
  scripts/ guard; or when a green trust-chain is about to be used as evidence for a claim. Carries the
  claim-provenance convention (mark every claim measured vs. remembered) and the map of what a green
  trust-chain does — and does not — prove. Entry point into veyq's doc↔code validation; per-guard detail
  lives in that guard's own module docstring, the mutation-testing deep-dive in ADR 0045.
---

# trust-chain

The doc↔code validation layer, as an entry point. This skill is **not** the guards' documentation — that is
each guard's own module docstring, whose required fields are defined once in
[`changes/README.md`](../../../changes/README.md) § trust-chain guards (`scripts/` is the list). This skill
adds only what no single file holds: the **one convention** that governs every claim, and the **map of the
chain's edges** — what green proves and where it stops.

## When this fires

- You are about to write "the docs are correct / coverage is green / this change is sound" — anywhere (a
  report, a CR, a handoff, a commit body).
- You add or change a `scripts/validate_*.py` guard, or lean on one as proof.
- You are reading a green build as evidence for anything.

## The one rule that travels everywhere: claim-provenance

**Every claim in a report, CR, or handoff is marked `measured` or `remembered`. A `measured` claim names
the command that proves it. A "measured" claim with no command is `remembered` wearing a command's clothes.**

This is the bite-proof idea ([`changes/README.md`](../../../changes/README.md) § bite-proof a guard) applied
to prose instead of tests: a claim you cannot re-run is a claim nobody can bite.

- **measured** — `measured (grep -rc '@Test' backend/…/FooTest.kt → 3): the slice has three cases`. The
  reader re-runs, not re-trusts. The command is the part that stays true while the code drifts; the number
  is spent the moment the go/no-go call is made ([`changes/README.md`](../../../changes/README.md) § the CR
  carries the locator).
- **remembered** — `remembered: coverage for uc.X looked complete last session`. Legitimate as a lead;
  never as a settled fact, and never the basis of an action. Before it drives a decision, measure it — that
  *is* the first step of any trust-chain task.

Why this convention and not a "be careful" plea: it was bought by measured failures. A remembered coverage
claim ("Trap 5") measured false; handoff notes three commits behind the git state; a full analysis written
over already-deleted Android code; an audit finding recommended still-open that had been deleted with its
surface. Every one was a *remembered* claim a one-line command would have refuted. The tell is the feeling
of already knowing ([`changes/README.md`](../../../changes/README.md) § upstream of every trap) — that is
exactly where the imported prior sits, and where this repo most often inverts it. Rationale in
[ADR 0046](../../../docs/decisions/0046-operative-lore-in-skills-and-agents.md) § 4.

**Delegate the measuring, but keep it measured.** When a claim needs reading across many files (a surface
sweep, "where does X live", "is Y consistent"), delegate the read to a **read-only `Explore` subagent** so
the main context stays lean — and require it to **return each finding measured: the command it ran and that
command's output**, not a prose conclusion. A subagent that hands back "coverage looks complete" has
laundered a `measured` claim into a `remembered` one across the delegation boundary; you inherit its
confidence without its evidence. Ask for the ledger, not the verdict — then the delegation costs context
without costing provenance.

**This skill obeys its own rule.** Every factual claim below is a pointer to its canonical home, or is
marked `measured` and carries the `file:line` it was read from — re-runnable, not recalled. Nothing here is
remembered.

## What green proves — and where it stops

**Green means: nothing points into the void. It does not mean: what it points at carries weight.**
`measured` (per-file read of every doc↔code `validate_*.py` + both `mutation_meta_check*.py`):
each asserts a reference **resolves / exists / is present / count > 0** — and, where a degenerate miss is
cheaply decidable, that the target is the right *kind* of thing (a web coverage ref is a `*.test`/`*.spec`
file, a backend ref has a non-empty body); it never asserts that what it points at **carries weight** — a
`*.test` file whose body asserts nothing still passes (oracle correctness is edge #1 below). The meta-guard
`validate_guard_gaps.py` is the exception that proves the rule: it asserts these very limits still hold, and
pins each closed hole so it can't silently re-open — below. The per-guard limit is a **required field** of that
validator's docstring (e.g. `validate_field_visibility.py`: "a marker permits, it does not verify") — see
[`changes/README.md`](../../../changes/README.md) § trust-chain guards, which also says why an *unwritten*
limit shrinks the meta-guard's reach. This skill only names the pattern.

Concrete: the specific gaps are **executable, not prose** — `scripts/validate_guard_gaps.py` (in the
trust-chain glob) injects each into a throwaway copy of the tree and asserts the guard still passes (or, for
the one working-check it pins, still rejects); its docstring is the enumeration. That guard is the canonical,
always-current list, so this skill does not restate it: a restated list is a *remembered* claim that drifts
the moment a validator is tightened — the very failure this section is about, one level up. The coupling is
deliberate — closing a gap flips a documented pass/fail and turns that guard red, forcing the closure to be
recorded here, not landed silently. **Read its line before citing a local green as evidence:** locally it prints
`SKIPPED` while no guard changed since the checkout's last green full run (CI runs it in full when a guard
changed, and nightly — § The map), and a skip re-checks nothing — after renaming what a forgery anchors on, run it
with `--force`.

Four documented gaps sit **outside** that guard, on purpose:

- **the release-identity guard's two ends**
  ([ADR 0057](../../../docs/decisions/0057-release-identity-and-deployment-recipe.md)).
  `validate_release_identity.py` goes further than the rest of the glob — it *executes* `ci.yml`'s
  version step offline and asserts what a publish would stamp — and still stops at two places no file-forging
  meta-guard can reach. It cannot inspect a **built image**, because the chain is buildless, so it proves the
  `Dockerfile` *declares* the deployment-recipe copy and never that the file is in the image (that is proven
  by building it and reading the file back out). And it cannot prove a **publish ran**: this repository has no
  remote and no workflow has ever executed, so the refusal-to-republish and the `v<major>.<minor>` ref stay
  statements about a file — an explicitly stated ADR 0040 gap, in the same shape as the coverage flip that ADR
  still leaves owed.

- the mutation **total-vs-per-glob** asymmetry: `mutation_meta_check.py` (crypto) asserts a **total** mutant
  count > 0 (`:53-55`); the **backend** one is **per-glob** (`mutation_meta_check_backend.py:54-63`). (The
  remembered "total-count" claim held for the crypto check only — corrected here.) Pinning it would need a
  synthetic report fixture + a git tree (the web check gates on `git diff`), so it belongs to a
  mutation-job-local test, not the buildless glob — and it is self-documented in both meta-check docstrings,
  beside the code.
- the **rate-limit posture sweep's declaration ≠ enforcement** boundary
  ([ADR 0051](../../../docs/decisions/0051-declarative-rate-limit-posture.md)):
  `backend/src/test/kotlin/me/raddatz/veyq/core/ratelimit/RateLimitPostureSweepTest.kt` asserts every JAX-RS
  endpoint **declares** `@RateLimit(key)` or `@RateLimitExempt(reason)` — it does **not** assert the
  domain-keyed limiters (`SHARE`/`RECIPIENT`/`OWNER`/`TENANT`) actually **fire**; only the per-endpoint runtime
  `429` tests do (`ShareIdentityBoundTest`, `SharePasswordAttemptBoundTest`, `InviteMailRateLimiterTest`). It is
  a **JVM test needing a Gradle build**, so it is outside the buildless glob for the same reason as the mutation
  asymmetry, and outside `validate_guard_gaps.py`, which forges files and runs a *Python* validator as a
  subprocess. Its second, narrower blind spot is **test-scope resources**, excluded by file existence in the
  test output dir — `DefaultDenyTest`'s deliberately annotation-free probe must stay annotation-free.
- **history-blindness** (e.g. a deleted cited ADR that now resolves to nothing) is a **principled edge** (§4
  below), not a fixable gap — a guard never sees history, so a forgery-assertion can't reach it. (The old
  "burned CR number" instance of this is gone entirely: CRs are named by slug, not an allocated number — nothing
  to reuse, so nothing to guard. Retired inbox #107; see `changes/README.md` § naming.)

### The four edges are principled limits, not gaps to fill

Adding more validators is **explicitly not the goal**. The chain has four ends no `validate_*.py` can close;
each has a canonical home — point there, don't try to build past it:

1. **Oracle correctness** — a guard never proves a test's assertion proves anything (`fun seal(k,s)=s`
   passes coverage). [ADR 0045](../../../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md)
   closes this on the *unit layer* via mutation score; nowhere else.
2. **Timing** — a test counts only when CI runs it, and *whether it ran* is a fact about a run, not a file.
   The **declared** half is checked: `validate_coverage.py` holds a backend ref's JUnit gates against the
   Gradle runs the workflows declare (pinned by `validate_guard_gaps.py` 17/18), and it refuses any ref on a
   surface whose job no run has executed (`ADMITTED`, pinned by 24; admission itself is a hand edit that
   nothing checks). The **run-time** half is the
   edge — a body that `assume`s itself away (pinned open, 21), a variable a script exports, a web `skipIf`, a
   job that executed nothing: green there still means only that the ref resolves.
   [ADR 0040](../../../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) +
   [`ai/README.md`](../../../docs/ai/README.md) § what counts as verified.
3. **Cross-feature** — when a rule was *never written*, the doc↔code delta is empty and the defect is still
   real (trash × sharing served trashed media). [`ai/README.md`](../../../docs/ai/README.md) § the pipeline,
   "what this step cannot see".
4. **Scope-completeness** — a guard sees only the present tree, never history: a cited ADR that was deleted
   (inbox #95), a CR number burned by a since-deleted CR (inbox #107). Green means nothing *currently*
   dangles. Reuse-checks must grep the working tree, not `git log`.

## The map (assembled from nowhere single)

`measured`:

- **Three CI runs, three triggers.** `trust-chain.yml` — unfiltered, buildless, runs the guard glob minus
  `validate_guard_gaps.py` (`:16-26`, no paths filter); that one is its `guard-gaps` job (`:101-119`), gated
  by a `dorny/paths-filter` on `scripts/**` and `.claude/skills/trust-chain/**` (`:74-99`) plus a nightly
  `schedule` (`:24-25`) — ADR 0091 decision 4, held by `validate_pipeline_shape.py` assertion 2. `ci.yml` —
  the one pipeline per commit (ADR 0091); its `guards` job runs the same glob minus `validate_guard_gaps.py`,
  and `paths-ignore` includes `docs/**` (`:28-33`, `:38-43`), so a docs-only change is validated *only* by
  trust-chain. `mutation.yml` / `mutation-backend.yml` — path-gated onto their scope files (`:16-49` /
  `:17-73`).
- **The glob is the registry.** `trust-chain.yml:48-70` runs `for f in scripts/validate_*.py` with a
  `count == 0` backstop — a new `validate_*.py` runs automatically, no registration. The
  `mutation_meta_check*.py` sit **outside** that glob (filename prefix) and run only from the mutation jobs.
  Run every guard locally the same way: `for f in scripts/validate_*.py; do python3 "$f"; done`.
- **The one coupling that bites differently per surface:** **web coverage refs are path-bound, backend refs
  are symbol-bound** (`validate_coverage.py`: web/crypto/admin-web `(root / ref).exists()` :666-673; backend
  `ClassName.method` via the `KOTLIN_FUN` regex over masked source + stem map :223, :443-468). The same move
  hits them differently — relocating a file breaks web refs; renaming a symbol breaks backend refs. This is
  what broke in cr-0175; carry it into any relocation's blast-radius grep.

## Procedures — pointers, not restatements

- **Run the guards:** `for f in scripts/validate_*.py; do python3 "$f"; done` (the CI glob, unfiltered).
  A red guard means a CR is not done ([`changes/README.md`](../../../changes/README.md) § trust-chain guards).
- **Bite-proof a new/changed guard:** the snapshot-and-restore protocol (never `git checkout`) lives in
  [`changes/README.md`](../../../changes/README.md) § bite-proof a guard — follow it there. Bite-test a
  guard *before* trusting its design: write the case that must make it FAIL first.
- **Coverage is part of the CR, not a follow-up:** [`changes/README.md`](../../../changes/README.md) §
  coverage is part of the work; the rule underneath is [ADR 0040](../../../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md).
- **Promote a one-shot proof to a permanent guard** when a CR rests on a structural invariant:
  [`changes/README.md`](../../../changes/README.md) § promote one-shot proofs. A new `validate_*.py` joins
  the glob for free — that is the whole point of the glob.

## Canonical sources (this skill restates none of them)

- [`docs/ai/README.md`](../../../docs/ai/README.md) — the workflow + coverage rules + what counts as verified.
- [`changes/README.md`](../../../changes/README.md) — glob-is-registry, bite-proof, the locator rules.
- [ADR 0040](../../../docs/decisions/0040-coverage-gaps-and-http-not-a-surface.md) — a test counts only when CI runs it.
- [ADR 0045](../../../docs/decisions/0045-mutation-testing-trust-chain-unit-layer.md) — the assertion-strength last link.
- [ADR 0046](../../../docs/decisions/0046-operative-lore-in-skills-and-agents.md) — why this skill exists at all.
- Each `scripts/validate_*.py` module docstring — that guard's whole documentation; its required fields are
  defined in [`changes/README.md`](../../../changes/README.md) § trust-chain guards.
