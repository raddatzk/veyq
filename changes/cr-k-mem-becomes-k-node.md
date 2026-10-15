---
id: cr-k-mem-becomes-k-node
title: The node content key is called K_node everywhere
status: planned
satisfies: []
adrs: ["0086", "0012", "0080"]
invariants:
  - "Operator-blind E2EE: no byte changes — seal purposes, derivation labels and vectors carry no K_mem and stay as they are"
  - "API-first: the contract descriptions change in contracts/ and the generated clients are regenerated, never edited"
---

## Goal

Rename the content key every node carries from `K_mem` to `K_node`, as ADR 0086's word table now decides: in the
docs, ADRs, use-cases and coverage texts, the contract descriptions (and therefore the generated clients), code
comments, the docs site, and the `kMem` identifiers in `web/src/features/memory`. `K_mem` dates from when only a
Memory had a key; since ADR 0080 every node does — a Memory, a Chapter, a folder, a Depot file or drop box. Nothing
on the wire carries the name, so this is a vocabulary change with no crypto or format effect.

## Context & constraints

- [ADR 0086](../docs/decisions/0086-one-word-per-meaning.md): the `K_node` row (identifier `kNode`; never
  `K_mem`) and its Consequences line.
- [ADR 0012](../docs/decisions/0012-content-key-hierarchy.md) and
  [ADR 0080](../docs/decisions/0080-every-node-carries-a-key-a-folders-key-behaves-like-a-memorys.md): the
  hierarchy (`K_SC` → `K_node` → `K_blob`; `K_share` wraps `K_node`). `K_SC`, `K_blob`, `K_share` and `K_up` keep
  their names.
- Measured at refine on `main`: no seal purpose, HKDF info string or vector field contains the name (the vector
  labels with "mem" are `member-avatar`, `member-name`, `share-memory-name` — unrelated); `kMem` occurs only in
  `web/src/features/memory` (6 files); `K_node` already appears once (`web/src/core/sharing/dropKey.ts`).
- ADRs are current state edited in place (`docs/decisions/README.md`), so they take the new name too; a sentence
  that is *about* the old name (ADR 0086's own row) keeps it.
- Other sessions commit into this checkout (Run and Use site pages, a backend store CR): stage only your own hunks
  in files they also touch.

### Locators

| What | Locator |
|---|---|
| Every prose and comment mention | `git grep -n "K_mem"` |
| The identifiers | `git grep -nw "kMem"` |
| The contract descriptions that generate into clients | `git grep -ln "K_mem" -- contracts` (then `generate:api` in `web/`) |
| Wire labels (must stay empty) | `grep -n "mem" contracts/crypto/vectors/v1.json \| grep -v "member-\|share-memory-name"` |

## Phases

- [x] 1 contracts + code (green: web check, gradlew check; vectors untouched) — the contract descriptions, regenerated clients, `kMem` → `kNode` and every code comment;
      the generated code is regenerated, not edited. · Done when: `npm run check` in `web/` and `./gradlew check`
      green; `git grep -nw kMem` returns nothing; the vectors file is byte-identical to `main`.
- [x] 2 docs + site (42036b0b, 2d8505d2; trust-chain and site check green — the final `git grep` waits for phase 1) — ADRs, use-cases, enablers, coverage texts, crosscutting docs, inbox, the docs site (EN and
      DE); keep only sentences about the old name. · Done when: trust-chain green, `npm run check` in `site/` green,
      and `git grep -n "K_mem"` returns only ADR 0086's row.

## Definition of done

The repository says `K_node` for the node content key everywhere but in the one sentence that records the old
name; no byte of any seal, label or vector moved; web, backend, site and the trust-chain are green.

## Retrospective

(filled at close)
