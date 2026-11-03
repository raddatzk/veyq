# ADR 0046 — Operative lore lives in `.claude/` skills and agents, not a growing CLAUDE.md

- Status: Accepted
- Built: yes
- Date: 2026-07-21

## Context

[CLAUDE.md](../../CLAUDE.md) is loaded on **every** task; its cost is paid per turn, forever.
[`ai/README.md`](../ai/README.md) already names this the "tax it pays on every task" and answers it for docs by
deleting duplication. The same tax applies to CLAUDE.md itself: it may hold only what is true for *every* task —
the hard invariants — or it taxes the 90% of turns that never touch the thing.

But operative lore has accreted that is **conditional**, not universal, and it has no home shaped for that. The
trust-chain map (glob-is-registry, the bite-proof protocol) lives in [`changes/README.md`](../../changes/README.md);
the coverage rules in [`ai/README.md`](../ai/README.md); per-guard detail in each `scripts/validate_*.py` module
docstring. Assembling that picture from four places is a per-session cost, and none of it is relevant when the
task is unrelated. Grown further, it would either bloat CLAUDE.md (taxing every turn) or the one workflow doc
whose job is to stay short enough that a human judging the project on GitHub reads it (§ audiences).

Three delivery mechanisms exist, and their difference is **load semantics**:

- **CLAUDE.md** — always loaded. Permanent context steering.
- **Skill** (`.claude/skills/`) — loaded on a trigger. A procedure with an explicit *"when"*.
- **Subagent** (`.claude/agents/`) — its own context. Isolation is the point: either the work would flood the
  main context (a delegated surface-reader that returns only conclusions), or unbiasedness is the goal. Which
  veyq work actually earns a subagent file is itself measured below — the answer turned out to be almost none.

What forced the decision now was a **measured pattern**, not a wish for structure: nearly every expensive recent
error was a *remembered* claim that a cheap measurement would have refuted — a coverage claim believed and later
measured false ("Trap 5"), handoff notes three commits behind the git state, a full analysis written over
already-deleted Android code, an audit finding recommended still-open that had been deleted with the surface it
lived on. That is not a knowledge gap. It is a missing **convention** about how a claim is stated — one that binds
exactly where claims are made, which is not every turn.

## Decision

1. **Three load-semantics, three homes. The decision rule:** *"if X, then procedure Y"* → a **skill**; *"do this
   in isolation / unbiased"* → a **subagent**; *"true for every task"* → **CLAUDE.md**. Conditional lore leaves
   CLAUDE.md for a skill or an agent role.

2. **Skills live in `.claude/skills/<name>/SKILL.md`, agent roles in `.claude/agents/<name>.md` — both committed.**
   `.claude/` is tracked (only `settings.local.json` is gitignored); these are durable repo artifacts, versioned
   beside the code they describe, not machine-local config.

3. **Point at the canonical source; never restate it** — the same invariant that governs `docs/`
   ([`ai/README.md`](../ai/README.md) rule 2). A skill or agent role adds only what no single doc already holds:
   its **trigger**, an **orientation/map** that is currently assembled by hand each session, and any **new
   convention**. Per-item truth stays in its one home — the validator docstring owns what that guard asserts, the
   ADR owns its rule, the use-case owns its behaviour. A skill that duplicates a doc is the defect, deleted like
   any duplication.

4. **Claim-provenance is a standing convention.** Every claim in a report, CR, or handoff is marked **measured**
   or **remembered**; a *measured* claim names the command that proves it. This is the bite-proof idea
   ([`changes/README.md`](../../changes/README.md) § bite-proof a guard) applied to prose instead of tests. It is
   carried by [`trust-chain`](../../.claude/skills/trust-chain/SKILL.md), whose trigger already names *a report, a
   CR, a handoff* — so the claim moments of CR execution are covered there, not in a separate skill (see the
   `cr-execute` finding in § Which proposed skills and roles survived measurement). It binds where claims are
   made, not on every turn.

5. **[`ai/README.md`](../ai/README.md) stays canonical for the workflow** — the prose narrative and the pipeline,
   read by both the refining agent and the human judging the project. Skills are its **agent-triggered operational
   face**: they link back into it, they do not replace it. CLAUDE.md keeps only the hard invariants.

## Consequences

- **Incremental, and gated by the repo's own trigger** ([`ai/README.md`](../ai/README.md) § when to add a tool):
  *pain before tool* and *rule of three*. Both are met — the provenance failures recur across sessions (rule of
  three), and trust-chain assembly is a felt per-session cost (pain). Skills are added one at a time, each
  justified on its own; `trust-chain` is first and the rest wait behind it.
- **A skills layer is a fourth place a reader can look** — a real cost, paid down only by rule 3. The moment a
  skill restates a doc, both drift and the layer becomes a liability; the no-restate rule is what keeps it an
  asset.
- **Rejected — keep accreting in CLAUDE.md.** Every line taxes every unrelated task; the file grows monotonically
  and the trust-chain lore is noise to almost every turn. This is the status quo the ADR ends.
- **Rejected — fold it all into [`ai/README.md`](../ai/README.md).** That doc is read by a human judging the
  project in twenty minutes; the guard-gap map and a provenance-marking format are operational agent detail that
  is noise to that reader and would bloat the one doc that must stay short.
- **Rejected — one monolithic skill.** Triggers differ (trust-chain fires on *"assert a doc sound"*; cr-execute on
  *"run a CR"*); a single skill would always over- or under-load for the task at hand.
- **Rejected — subagents only, no skills.** A subagent's value is isolation; a procedure the main thread must
  follow *inline* (mark this claim measured, before you write it) cannot be handed to a fresh context. Skill and
  subagent are complements, not substitutes.
- **The no-restate test culls the layer to what earns its place.** Of **three proposed skills and four agent
  roles**, the measurement kept **one skill** (`trust-chain`) + **one guard** (`validate_skill_links.py`) and a
  one-paragraph reader convention; it rejected `cr-execute` and `mutation-gate` as ~100% restatement, and all four
  roles (the decisive reason: a subagent cannot enforce a top-level-session split — below). The per-item verdicts
  are recorded below (§ Which proposed skills and roles survived measurement) — that finding is worth more than the
  artefacts would have been, and is the no-restate invariant holding against the skills layer itself, which is the
  point.

One question is open: whether claim-provenance also earns a one-line anchor in the root CLAUDE.md, so it loads even
outside a triggered skill. It waits on whether the trigger coverage shows real gaps; deciding it without that would
be guessing.

## Which proposed skills and roles survived measurement

The rollout proposed three skills and four agent roles. Each was measured against rule 3 — *for every element it
would carry, does a canonical home already exist?* — and `pain-before-tool` was applied strictly: only a
**recorded** failure counts, never an imaginable one. Most did not survive; the tables and the roles finding are
kept here because a deleted work-order (the CR that found this) cannot hold them and the finding outlives it.

**`trust-chain` — BUILT.** It consolidated a map scattered across four places *and* introduced content with no
prior home: the claim-provenance convention (§ 4) and the guard-gap synthesis ("green = a reference resolves, not
that it carries weight"). Genuinely new — it passes rule 3.

**`cr-execute` — CHECKED, NOT BUILT.** Every element already has a canonical home; its claim-provenance role is
covered by `trust-chain`'s trigger (which names *"a report, a CR, a handoff"*):

| element it would carry | canonical home (measured) |
|---|---|
| phase order contracts→backend→web | `changes/README.md` § phases + [`ai/README.md`](../ai/README.md) § what counts as verified |
| each phase self-verifies ("Done when") | `changes/README.md` § phases |
| surface check cmd (+ `generate:api` *before* `check`) | [`src/backend/CLAUDE.md`](../../src/backend/CLAUDE.md), [`src/web/CLAUDE.md`](../../src/web/CLAUDE.md) |
| coverage is part, not a follow-up | `changes/README.md` § coverage is part of the work |
| trust-chain guards green via the glob | `changes/README.md` § trust-chain guards |
| promote a one-shot proof → permanent guard | `changes/README.md` § promote one-shot proofs |
| bite-proof snapshot-restore | `changes/README.md` § bite-proof a guard |
| close with a spoken retro | [`ai/README.md`](../ai/README.md) § Context topology (the closing process retro) |
| mark claims measured | the `trust-chain` skill + [`ai/README.md`](../ai/README.md) § the pipeline (measured, not recalled) |
| workflow entry | [`ai/README.md`](../ai/README.md) (the entry for every working session) |

**`mutation-gate` — CHECKED, NOT BUILT.** Covered even more completely — [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md)
is a complete decision + consequences, and the two meta-check docstrings self-document their asymmetry.
No failure is on record *after* ADR 0045 that a skill would have prevented (the two known mutation missteps were
caught inside the refine that wrote 0045, and its corrections are in the doc):

| element it would carry | canonical home (measured) |
|---|---|
| concept: mutation = assertion-strength last link | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) Context + Decision |
| per-surface finding (web KAT-pinned, backend no content crypto) | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) § Context table |
| tool choice (Stryker/PIT, no kotlin-plugin, no arcmutate) + why | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) § Decision 1 |
| backend three-layer split, what is mutated | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) § Decision 2 |
| what is in scope + the discriminator | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) § Decision 2–3 + the two `stryker.mutate.json` manifests (`src/web/`, `src/packages/crypto/`), `src/backend/core/pitest.scope.json` |
| CI shape: PR, path-gated, report-before-gate | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) § Decision 4 + `mutation.yml`, `mutation-backend.yml` |
| the total-vs-per-glob meta-check asymmetry | `scripts/mutation_meta_check.py` + `scripts/mutation_meta_check_backend.py` docstrings |
| KAT-beats-mutation finding; blind spots | [ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md) § Context + Consequences |
| operational: `pnpm run mutation` PR-only; in-place footgun | [`src/web/CLAUDE.md`](../../src/web/CLAUDE.md) |

The residual non-restate value of either — a trigger at the work's start and one ordered checklist — rests on an
**unmeasured** failure (a session skipping the canonical entry), so it stays deferred until such a failure is
observed.

### The four agent roles — measured, and mostly culled

The rollout also proposed four agent roles (`refine`, `implement`, `verify`, `measure`) as `.claude/agents/`
files. The measurement collapsed them, and the reason is the single most reusable finding of this whole exercise:

> **A subagent is a *child* of the conversation that starts it. A write-boundary on a `refine` subagent stops
> that subagent from writing, but not its parent from implementing. So the boundary the role exists to enforce
> would be a *false guarantee*. The split is enforced by where the work runs ([`ai/README.md`](../ai/README.md) §
> Context topology) and by owner review, and no agent file can replace either.**

That reframes the whole idea. Refinement is **conversation-shaped**: it runs with the owner. Implementation
does run in a subagent, an ordinary general-purpose one whose brief is the CR's path. That is the clean room
(§ Context topology), and a role file would add nothing the brief does not already carry. To make a
*top-level* session read-only you set session config (permissions / `permissionMode`), not a `.claude/agents/`
file, which only ever governs a *delegated* child. The one genuinely subagent-shaped, read-only piece is the
delegated reader (`measure`), and that is already the built-in `Explore` agent (all tools except Edit/Write/
NotebookEdit); its only veyq-specific delta — *return findings measured, with the command* — is a one-paragraph
convention in the `trust-chain` skill, not a new role file. `verify` has **no recorded failure** (its class of
miss — a coverage claim believed then measured false — is a provenance failure, already closed by § 4 + the
`trust-chain` guard-gap map) and overlaps existing review tooling. Net: **zero role files built.**

**Measured harness capabilities behind the finding** (Claude Code subagent + permissions docs, checked
2026-07-21 — re-verify, as harness behaviour drifts):

- Subagent frontmatter `tools:` is a comma-separated **allowlist** (omitted ⇒ inherits all); `disallowedTools:`
  is a **denylist**, applied first. So "no writes at all" *is* expressible with the tool list alone, zero extra
  mechanism (`tools: Read, Grep, Glob, Bash`, the built-in `Explore` form) — this part of the idea works.
- **Permissions are session-global, not per-agent.** No frontmatter field scopes a write path to one role;
  `permissionMode` is a mode, not a boundary. A global `Edit(changes/**)` rule would bind the *main thread* too.
- **`Write(path)` is a footgun** — accepted but never matched (warns at startup); the working form is
  `Edit(path/**)`, which covers all file-editing tools.
- The *only* per-agent path scope is a per-agent frontmatter **hook** running a checked-in script that inspects
  `tool_input.file_path` and denies — extra machinery (a bash hook + script) for a boundary the subagent couldn't
  make load-bearing anyway.

**Rejected — `refine`/`implement`/`verify` as `.claude/agents/` role files** (recorded so this is not
re-derived): a write-boundary on a child subagent cannot enforce a top-level-session invariant, `verify` has no
recorded pain and duplicates review tooling, and the read-only-`refine`-returns-text option — though the *only*
zero-mechanism form — mis-sizes the handoff: `refine`'s real job reshapes durable state (an ADR + use-case edits
+ the CR), and handing multi-file *edits* back as text for a caller to apply is a faithful-copy cost that lands on
the common case. Kept: the `measure`-as-`Explore` convention in `trust-chain`. The context topology + owner review
carry the split; that is the result, not a failure to build.
