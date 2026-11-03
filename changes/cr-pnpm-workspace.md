---
id: cr-pnpm-workspace
title: The JavaScript packages are installed by pnpm, through a deprecation gate rewritten for it
status: done
satisfies: []
adrs: ["0116", "0065", "0087"]
invariants:
  - every resolved package version stays what the npm lockfiles held, except where pnpm import dedupes inside a declared range
  - every install a build runs goes through the deprecation gate, or installs a lockfile a gated job scanned on the same commit
  - one pnpm version, pinned exactly, everywhere a build installs it
---

## Goal

Switch the repository's JavaScript package management from npm to pnpm (owner go 2026-11-03), so every git worktree
links packages from pnpm's shared store instead of carrying its own ~470 MB `node_modules`, and pnpm's strict
`node_modules` closes the "undeclared import passes locally" trap. The decision, its alternatives and their sources
are [ADR 0116](../docs/decisions/0116-the-javascript-packages-are-installed-by-pnpm.md).

## Inventory

Locator: `git grep -n -P "(?<![\w./@-])(npm|npx)(?![\w-])|package-lock|npm_ci_no|npm_installs"` over the tree (the
lockfiles and `src/backend/gradle/verification-metadata.xml` excluded). What remains after the CR is historical
narration (what an npm run did at the time), the pnpm bootstrap `npm install -g pnpm@<x>`, and the upgrade proof's
install of a predecessor that predates the move.

| Surface | Change |
|---|---|
| root + `site/` manifests | `pnpm-workspace.yaml` (members, overrides, `allowBuilds`), `packageManager`, `workspace:*`, lockfiles by `pnpm import` |
| the gate | `scripts/pnpm_install_no_deprecated.py`, `scripts/validate_pnpm_installs_gated.py` (assertion 0: one pnpm) |
| CI + images | `ci.yml`, `mutation.yml`, `site.yml`, both Dockerfiles, the L1 compose overlay, `dependabot.yml` comments |
| launcher + scripts | `launcher/*`, `run_web_e2e.py` (predecessor install), `run_stack_e2e.py`, `run_api_fuzz.py`, `measure_volume.py`, `validate_doc_citations.py` |
| docs | root and surface `CLAUDE.md`, `infra/README.md`, the Security pages that give commands, ADRs 0065/0087/0090/0106 wording |

## Done when (measured 2026-11-03)

- `python3 scripts/pnpm_install_no_deprecated.py` green at the root (647 locked packages asked of the registry, 14.6 s)
  and with `--package-dir site` (570).
- `pnpm run check` green in `src/packages/crypto` (914 tests), `src/admin-web` (569, CLI bundle built), `tests/e2e`
  (172 tests listed in 37 files), `src/web` (6052 passed, 38 skipped; every `verify:*` green), `site` — in this
  worktree and again in a checkout outside the repository, where no outer `node_modules` can satisfy an import.
- Every `scripts/validate_*.py` green; `validate_guard_gaps.py --force` green (104 cases).
- `docker build -f src/admin-web/Dockerfile .` green; the app image's `frontend-build` stage green.
- The gate bitten red by a scratch package locking `request@2.88.2`, with and without the lockfile's marks; green on
  `is-number@7.0.0`. The install guard bitten red by eleven injected mutations, green after each restore.

## Retrospective

- **The substrate lied about strictness.** This worktree sits inside the main checkout, so Node resolved undeclared
  imports from the main checkout's npm-hoisted `node_modules`, and the in-tree checks were green for a reason that is
  not the code. The clean checkout outside the repository found two more (`vue-eslint-parser` in both clients' eslint
  rule tests, `sharp` for `astro build`) beside the two found by reading (`tsx`, run unpinned by `npx`;
  `@noble/ciphers` in the e2e AEAD profile). Moved to `src/admin-web/CLAUDE.md` and ADR 0116 § Consequences.
- **A frozen pnpm install warns about nothing.** The npm gate leaned on `npm ci`'s own warnings for installed packages
  and asked the registry only about skipped ones; under pnpm the warning never comes, so the registry half had to
  grow to the whole lockfile. Found by the bite, not by reading pnpm's docs — the gate's docstring now says so.
- **An up-to-date install runs no `prepare`.** The launcher's bootstrap regenerated clients through npm's `prepare`;
  under pnpm that silently stopped, measured only because the launcher comment claimed a behaviour.
