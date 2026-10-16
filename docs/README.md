# veyq engineering docs — map & rules

This is `docs/` — veyq's internal engineering **state** (English), the durable truth. It is updated in place,
never a dumping ground for raw ideas (those go to [`inbox.md`](inbox.md) → refined into state; see the workflow
in [`ai/README.md`](ai/README.md)).

**Every document has exactly one *kind*.** The main hygiene failure is putting content in the wrong kind — a
mechanism narrated in a use-case, a decision made in a use-case, the same model copied into three docs. **This
page is canonical for *what belongs where*.** (Per-kind *formats* live in the sub-READMEs linked at the bottom.)

## The map

```
docs/
  README.md                         # ← you are here: the canonical map + rules
  features/<feature>/
    _index.md                       # feature overview + scope + cross-cutting links
    use-cases/uc.<feature>.<goal>.md
    enablers/en.<feature>.<name>.md
  crosscutting/                     # cross-feature concerns/models + styleguides (some cc.*)
  apps/                             # applications on the platform (ADR 0079): Memories, plus concept-only docs
  decisions/NNNN-<slug>.md          # ADRs
  architecture/README.md            # the synthesized whole-system view (the map above all)
  coverage/                         # acceptance coverage, mirrors the use-case tree
  ai/README.md                      # working method — owns the pipeline
  inbox.md                          # flow, not state — raw topics awaiting refinement
```

## The five kinds

| Kind | Answers | Contains (altitude) | A CR can `satisfies:` it? | A wiki page speaks for it? |
|---|---|---|---|---|
| **Use-case** `uc.*` | *What can a user **do**?* | user-observable behavior + failure modes | **yes** | **yes** — its EN+DE section is the page's raw material |
| **Enabler** `en.*` | *What **feature-scoped** foundation makes use-cases possible?* | mechanism / how-it's-built (one feature) | **yes** | yes, when the page explains the model (`en.account.key-model`) |
| **Crosscutting** `cc.*` / `crosscutting/` | *A **cross-feature** concern or model?* | mechanism/model/convention spanning features | **yes** | yes (`security-model.md`) |
| **ADR** `decisions/` | *Why did we **decide** it this way?* | one settled choice **+ its rejected alternatives** | **no** — referenced via `adrs:` | no |
| **Architecture** `architecture/` | *How is the **whole system** built, right now?* | synthesized overview that **indexes** the rest | no (a map, kept current) | no |

## The rules per kind

**Use-case (`uc.*`)** — *what the user does.*
- **Belongs:** actor + goal, main flow, alternate/exception flows (user-observable steps), pre/postconditions,
  quality notes, the one-line English `## User impact` (the user's own text lives on the docs site's Use pages).
- **Does NOT belong:** storage/wire format, key wrapping, crypto formulas, endpoint threading, or a decision +
  its rejected alternatives. State a feature's *concept* only as far as the flow needs it. Nor a **delivery
  roadmap / slice plan** (which CRs ship what, in what order) — that is *planning*: it lives in
  [`inbox.md`](inbox.md), then materializes as the CRs.
- **Altitude test:** "the user sets a location and names it" ✓ — "POST /nodes, then PATCH …/wrappedMeta" ✗
  (that's the *how*). Lean toward **user behavior**, not an API-call script. A UC also feeds the plain-language
  wiki + the acceptance criteria — content an API choreography could not.

**Enabler (`en.*`)** — *the feature-scoped foundation.*
- **Belongs:** the mechanism a feature's use-cases build on — data layout, key wrapping, sealing, endpoint
  threading; `Provides` / `Serves (use-cases)` / `Depends on`.
- **Even a deep crypto foundation is an enabler when it serves *one* feature** — e.g. account's key model and
  its passwordless-unlock orchestration serve only account's login/unlock use-cases, so they are `en.account.*`,
  not crosscutting.
- **Does NOT belong:** a *decision* with rejected alternatives (→ ADR; reference it); a *cross-feature* concern
  (→ crosscutting).

**Crosscutting (`cc.*` / `crosscutting/`)** — *the cross-feature concern/model.*
- **Belongs:** a concern / model / convention that serves **many features or applies everywhere** — the
  whole-system security model, scopes & tenancy (spans account + spaces + admin + memory), rate-limit, the
  ui/dev styleguides, inclusive language.
- **Does NOT belong:** a decision (→ ADR); a **single feature's** foundation (→ that feature's enabler, even if
  it is crypto).

> **Enabler vs. crosscutting = how many features it serves.** One → enabler; many / applies-everywhere →
> crosscutting.

**ADR (`decisions/`)** — *the decision.*
- **Belongs:** Context / Decision / Consequences, **including the rejected alternatives** — the durable *why*.
- **Does NOT belong:** a user flow; a how-to mechanism better placed in an enabler (keep only enough mechanism
  to justify the choice).
- **Current state, edited in place — no dated addendum logs.** An ADR states the decision **as it is now**;
  when it changes, **edit it** and let **git** hold the history (who/when/which CR — the CR's `adrs:` + the
  commit are the trail). Do **not** accrete `- Amended: <date> (cr-N)` lines or `## Decision — addendum
  <date>` sections. Keep the full current reasoning + rejected alternatives in the body (git holds *text*
  history, not the current substance). Whole-ADR replacement is the separate `Status: Superseded by NNNN`.

**Architecture ([`architecture/`](architecture/README.md))** — *the whole-system map.*
- **Belongs:** the synthesized views no single doc owns — container/component, layering, the key-hierarchy
  spine end-to-end, deployment topology, data-flow — plus an index of the ADRs/enablers/cc.
- **Does NOT belong:** new decisions (→ ADR) or per-feature mechanism (→ enabler). It **references**, it never
  duplicates.

## The litmus — sort any block of text

> *What the user does* → **use-case.** *How a mechanism is built* → **enabler** (one feature) or **crosscutting**
> (many). *A choice + its rejected alternative* → **ADR.** *How the whole system fits together* → **architecture.**

## Anti-patterns (the smells to move out)

1. A use-case section titled **"The model / The key model / The sealed X"** that explains storage, key layout,
   wire format, or crypto formulas → mechanism in a UC. Move to the enabler; leave a one-line pointer.
2. A use-case phrase **"extends ADR X" / "resolves ADR X's deferred question"** → a *decision* being made in a
   UC. It belongs in ADR X (the header itself is the tell).
3. **The same model narrated in ≥2 docs** → duplication. It lives **once** (enabler/cc/ADR); everywhere else
   **links**.
4. An **enabler or cc doc that *decides*** something with rejected alternatives → that is an ADR; reference it.
5. A **dated addendum inside an ADR** (`Amended: …`, `## Decision — addendum 2026-…`) → that is a change-log;
   fold it into the current-state Decision/Consequences and let **git** hold the history.

## How the kinds connect

- An **enabler** lists `Serves (use-cases)`; a **use-case** (or enabler) lists `Depends on` the enablers it
  builds on.
- A **change-request** (`changes/cr-*`) `satisfies:` **exactly one** `uc.` / `en.` / `cc.` and lists the
  `adrs:` it touches. **An ADR is never *satisfied* — only referenced.** So a change to a `uc`/`en`/`cc` can
  trigger a CR; **changing an ADR does not** (the *decision* it records may spawn CRs, which satisfy the
  `uc`/`en`/`cc` that builds it).
- **Architecture** sits above all and **indexes** them — a map kept current as work lands, not a satisfiable
  record.
- The wiring is machine-checked by **every** guard in `scripts/` (`scripts/validate_*.py`), not by a subset
  worth naming here. Each validator's module docstring is that guard's whole documentation (the three fields it
  must carry are defined once, in [`../changes/README.md`](../changes/README.md) § trust-chain guards), so
  **`scripts/` is the list** — this bullet points at it rather than restating it, because a list restated in
  prose drifts out of step with the one that actually runs. CI runs the glob, unfiltered on every push and PR,
  in [`../.github/workflows/trust-chain.yml`](../.github/workflows/trust-chain.yml) — all but
  `validate_guard_gaps.py`, which tests the guards and runs there when one changes, and nightly.

## Formats (the per-kind templates)

- Use-case + enabler format → [`features/README.md`](features/README.md)
- ADR format → [`decisions/README.md`](decisions/README.md)
- Coverage format → [`coverage/README.md`](coverage/README.md)
- Working method → [`ai/README.md`](ai/README.md)
