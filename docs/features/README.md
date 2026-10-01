# Features

Product capabilities. Each feature is a folder with an overview (`_index.md`), its **use-cases**, and its
**enablers**. A use-case is the unit of the trust chain *and* carries the plain-language account a wiki page
draws on; an enabler is shared foundation that several use-cases build on.

> **What belongs in which kind of doc** (use-case vs enabler vs crosscutting vs ADR vs architecture) is
> canonical in [`../README.md`](../README.md). This page covers the **format** of use-cases + enablers.

```
features/<feature>/
  _index.md                          # feature overview, scope, cross-cutting links
  use-cases/uc.<feature>.<goal>.md   # one concrete user-facing flow each
  enablers/en.<feature>.<name>.md    # shared foundation (no standalone end-user goal)
```

## Use-case vs enabler vs ADR vs change-request

Four different kinds of record — keep them distinct:

| | **Use-case (`uc.`)** | **Enabler (`en.`)** | **ADR (`docs/decisions/`)** | **Change-request (`changes/`)** |
|---|---|---|---|---|
| Answers | *What can a user **do**?* | *What foundation makes use-cases possible?* | *Why did we **decide** it this way?* | *How/when do we **build** it?* |
| Nature | end-user-visible goal (perceived value) | structural/infra; value only **through** the use-cases it serves | a settled choice + its rejected alternatives | a phased **work order** + acceptance |
| Durable state? | yes (durable truth) | yes (durable truth) | yes (durable truth) | **a record** of work, not state — links to the truth, never copies it |
| Wiki source? | **yes** (the bilingual section) | **no** end-user section | no | no |
| Test/trust-chain unit? | yes | yes (still verified) | no (a rationale, not a flow) | no — it *produces* the tested `uc.`/`en.` |
| Example | log in, set device security, share a Memory | `en.spaces.personal-scope`, `en.memory.tree` | unlock methods (ADR 0005), content-key hierarchy (ADR 0012) | cr-0026 (builds `en.spaces.personal-scope`), cr-0027 (builds `en.memory.tree`) |

**Litmus for UC vs enabler:** does the user have a conscious goal with perceived value from *this* step
alone (UC), or is it the substrate another flow needs (enabler)? An empty per-tenant scope or an empty named
folder is an enabler; "log in" or "share this Memory" is a use-case. When in doubt it is an enabler — promote
it to a use-case only when it stands on its own for a user.

**How they connect:** an **enabler** lists **Serves (use-cases)**; a **use-case** (or enabler) lists
**Depends on** the enablers it builds on; a **change-request** `satisfies:` a `uc.` *or* an `en.` and lists
the `adrs:` it touches; an **ADR** is referenced by the CRs and the uc/en it shapes. The durable truth lives
in uc/en + ADRs; the CR is the kept work-order trail beside it (see [`changes/README`](../../changes/README.md)).

## Use-case format (Cockburn-lite)

**Machine-checked** by `scripts/validate_feature_format.py` (in CI) — the header bullets, `ID` == filename, the
required sections, numbered flow steps, and the EN+DE pair. What it cannot check is whether any of it is *true*.

```
# UC: <Title>
- **ID:** uc.<feature>.<goal>
- **Primary actor:** <actor>              (plural + a "(success)"-style qualifier are fine)
- **Secondary actor:** <optional>
- **Goal:** <one sentence>
- **Preconditions:** …
- **Postconditions (success):** …

## Main flow
1. …                                      ← numbered steps, always

## Alternate / exception flows
- A1: … / E1: …

## Quality notes        (optional — invariants/threats this flow must respect)

## What this means for you / Was das für dich bedeutet
**EN —** plain language.  **DE —** dieselbe Aussage.
The plain-language source a wiki page draws on. The page itself is written by hand and names this doc
in its `speaks-for` header; this doc links the page back under ## Related.

## Related
Actors, cross-cutting concepts, decisions (ADRs), related use-cases.
```

**The flow is numbered, and that is the point of the document.** A use-case answers *how does this run* — step
by step, in order. Prose paragraphs that describe a mechanism are not a flow: they read as complete and leave
the next implementer guessing at the sequence. If a paragraph explains a rule rather than a step, it belongs in
`## Quality notes`; if it explains a structure, it belongs in the enabler. (`uc.memory.place` was four bold
paragraphs and no steps for months — correct, rich, and unusable as a build order.)

The bilingual section is the plain-language material behind `wiki/`, **not** the wiki itself. The wiki is a
small set of hand-written topic pages, each spanning several docs, and nothing generates it. What keeps a page
from drifting silently away from its docs is an address, not a derivation: line 1 of every page declares the
docs it speaks for and its other-language pair, and each declared doc under `docs/` links the page back, so a
change to the doc shows which published page to re-read (`scripts/validate_wiki_sources.py`).

## Enabler format

Like the use-case format, but: **`# Enabler: <Title>`**, **`ID: en.<feature>.<name>`**, a **Type: Enabler**
line, **Provides** (what foundation it gives) + **Serves (use-cases)** + **Depends on** (the enablers/UCs it
builds on) instead of a single Goal — and **no "What this means for you" section** (an enabler has no
standalone end-user story; its value surfaces through the use-cases it serves). Keep a short note saying so
where that section would be.

**An enabler owes no `## Main flow` — because enablers come in two kinds.** Nine of the thirteen describe a
*flow* (`en.memory.media`, `en.memory.tree`, the scope enablers) and carry one. Four describe a **model** — a
key inventory (`en.account.key-model`), a table layout (`en.memory.buckets`), an envelope shape
(`en.memory.node-meta`) — where there is no sequence to state and forcing one would mean inventing it. This
page used to demand "the same Main-flow / exceptions" from both; it was never true. **If an enabler does have
a flow, it is numbered like any other** — that half is checked.

**Diagrams.** Illustrate flows with inline Mermaid where it clarifies — typically a **sequence
diagram** for the main flow and a **state machine** for lifecycle/state, as diagrams-as-code (see
[`docs/ai`](../ai/README.md) for the format rule and the two characters that must be escaped). A broken
block shows as a render error on GitHub, so the doc itself is the check — open it there before calling a
doc done.
