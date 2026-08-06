# Architecture Decision Records (ADRs)

Short, numbered records of **stable** architectural or product decisions — the durable trace of *why*
something is the way it is. One file per decision: `NNNN-kebab-title.md`, numbered from `0001`.

- **Status:** `Proposed` · `Accepted` · `Superseded by NNNN`. Supersede when the decision *changes*; **delete
  when its subject is gone** — five went with the native surfaces (`7a1c4e9f`) and there was nothing to
  supersede them *to*. A deletion is not done until its citations are swept with it: ADRs are cited from
  `contracts/` and from code, **not only from `docs/`** — checked by `scripts/validate_adr_citations.py`. The
  native drop was thorough in `docs/` but left 27 references to the deleted ADR 0009 behind in a spec and the
  web crypto layer — swept only later, in cr-0178, by repointing them to the surviving ADR 0017. The gap that
  let them linger (no validator watched whether a cited ADR still resolves) is **now closed** by that guard,
  scoped to the load-bearing surfaces (inbox #95).
- Keep them concise: context, the decision, consequences — not a tutorial.
- **An ADR records a decision that had a real alternative.** If it could not have gone another way, it is a
  description, not a decision — put it where it binds instead of filing one. Apply the test before adding a
  record, not in a review afterwards.
- Refinements that produce a durable decision create an ADR; change-requests link the ADRs they touch.
- **Current state, edited in place.** State the decision *as it is now*; when it changes, **edit the ADR** and
  let **git** hold the history — **no** `Amended: <date>` lines or `## Decision — addendum <date>` sections
  (a change-log belongs in git, not the doc). Keep the full current reasoning + rejected alternatives. Canonical
  rule: [`../README.md`](../README.md).

## Format

```
# ADR NNNN — Title
- Status: Accepted
- Date: YYYY-MM-DD

## Context
What forces the decision.

## Decision
What we chose.

## Consequences
What follows — including the costs and the alternatives rejected.
```
