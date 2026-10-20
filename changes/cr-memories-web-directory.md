---
id: cr-memories-web-directory
title: The web's Memories directory is named for its owner — web/src/features/memories
status: in-progress
satisfies: []
adrs: ["0079"]
invariants:
  - no core file imports an application, no application another (validate_web_core_imports_no_applications.py)
  - a moved web coverage ref still resolves (web refs are path-bound, validate_coverage.py)
---

## Goal

Inbox #324's first residual. ADR 0079 Consequences: "Not done on the web: the directory is still named
`features/memory`". Every other place names Memories for its owner, `memories` — the backend module `:apps:memories`,
the packages `me.raddatz.veyq.memories`, the specs `contracts/api/memories/`, `docs/apps/memories.md` — and Depot, the
second application, already lives in `web/src/features/depot`. Move `web/src/features/memory` to
`web/src/features/memories`, re-point every reader, and let the web guard derive an application's directory from its
owner name, so a directory named otherwise cannot be what the guard reads. No product decision: the target name is
the owner name the build already uses.

## Context & constraints

- [ADR 0079](../docs/decisions/0079-veyq-is-a-platform-memories-is-its-first-application.md) Consequences (the web
  half) and [docs/apps/](../docs/apps/README.md).
- Out of scope, and NOT renamed: the use-case docs `docs/features/memory/` and their coverage
  `docs/coverage/features/memory/` (the product feature "Memory", not the web directory), `e2e/tests/features/`
  (Playwright's own tree), the i18n namespaces `memory.*` / `media.*` (keys, not paths), the product word Memory.
- Readers, measured on 8c8b9e10 (`git grep -l 'features/memory'`, then the docs' own `../features/memory` link
  targets set apart): the vitest imports and `vi.mock` paths, `web/scripts/*.mts`, `web/eslint/featureMocks.test.mjs`,
  ten e2e specs' fixture URL, `scripts/make_exif_fixture.py`, `scripts/upgrade_proof_selftest.py`, the coverage YAMLs'
  `src/features/memory/…` refs (path-bound), the guard's `APPLICATIONS` and `validate_guard_gaps.py`'s forgeries
  (which anchor on the directory and raise `ForgeError` when it is gone), comments and the docs' backticked paths.
- `web/CLAUDE.md` and the root `CLAUDE.md` name the old path; they are the owner's to edit — proposed text goes with
  the hand-off.

## Phases

- [ ] 1 web — `git mv` the directory; a script rewrites every `features/memory` that names the web directory (word
  boundary, skipping a `docs/`/`coverage/` prefix and a markdown link target); `APPLICATIONS` derives
  `features/<owner>`; the guard-gaps forgeries re-anchor. · Done when: `npm run check` in `web/` green,
  `for f in scripts/validate_*.py; do python3 "$f"; done` green, `validate_guard_gaps.py --force` green, and
  `git grep -nE '(^|[^A-Za-z0-9_])features/memory([^A-Za-z0-9_-]|$)'` lists only docs-tree paths.
- [ ] 2 docs — ADR 0079 Consequences, `docs/apps/`, inbox #324; this CR deleted.

## Definition of done

As the phases. No use-case changes behaviour, so no coverage row is added; the coverage YAMLs move their refs only.
No docs-site page names the web directory (`git grep -n 'features/memory' -- site` lists only `docs/features/memory`
links).

## Retrospective

(at close)
