# Architecture Decision Records (ADRs)

Short, numbered records of **stable** architectural or product decisions — the durable trace of *why*
something is the way it is. One file per decision: `NNNN-kebab-title.md`, numbered from `0001`.

- **Status:** `Proposed` · `Accepted` · `Superseded by NNNN`. Supersede when the decision *changes*; **delete
  when its subject is gone** — five went with the native surfaces (`7a1c4e9f`) and there was nothing to
  supersede them *to*. A deletion is not done until its citations are swept with it: ADRs are cited from
  `contracts/` and from code, **not only from `docs/`** — checked by `scripts/validate_doc_citations.py`. The
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

- **What is true now lives in the topic's current-picture doc**, named by `- Model:` and listed under
  [Topics](#topics); an ADR keeps the choice and its why. A decision that changes part of an earlier ADR edits
  that doc and gives the earlier ADR one `- Replaced in part:` line, never a mark in its body. Canonical rule:
  [`../README.md`](../README.md).

## Format

```
# ADR NNNN — Title
- Status: Accepted
- Built: partly (not built: § 3, decision 7)
- Date: YYYY-MM-DD
- Model: <a link to the topic's current-picture doc>
- Replaced in part: § 2, § 4 by ADR NNNN (not built)

## Context
What forces the decision.

## Decision
What we chose.

## Consequences
What follows — including the costs and the alternatives rejected.
```

The header lines, each one line:

- **`Status`** — `Proposed`, `Accepted` or `Superseded by NNNN`, and nothing else.
- **`Built`** — `yes`, `no` or `partly (not built: …)`, naming the parts still missing as the ADR numbers them. A
  decision about method or documentation is built once it is followed. It says *what* is missing, never how the
  rest was built — the code, its tests and `coverage/` hold that.
- **`Date`** — the day it was decided, alone.
- **`Model`** — the topic's current-picture doc; left out while the topic has none.
- **`Replaced in part`** — only when a later ADR replaced part of this one; one line naming every such ADR.

None of them carries a date beyond `Date`, a CR id or an owner's answer. An answer that still binds is part of
the decision: it goes into the body, as an obligation.

