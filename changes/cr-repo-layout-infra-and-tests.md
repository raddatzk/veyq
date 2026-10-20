---
id: cr-repo-layout-infra-and-tests
title: Stage 1 of the repository layout — deployment under infra/docker and infra/kubernetes, the harnesses under tests/
status: in-progress
satisfies: []
adrs: ["0106", "0057", "0064", "0090", "0091", "0098"]
invariants:
  - nothing but paths changes - no behaviour, no image content, no chart output, no test semantics
  - the dev stack's Compose project stays `infra` - its volumes (`infra_veyq-*`) and network (`infra_default`) survive the move
  - the OCI registry path `ghcr.io/<owner>/charts/veyq` is a name, never rewritten
  - a measurement record (a grep and its file:line results in an ADR's ledger) is not rewritten; instructions, links and shape descriptions are
  - the owner's running dev containers and main checkout are not touched by the implementer
---

## Goal

Build stage 1 of [ADR 0106](../docs/decisions/0106-deployment-under-infra-tests-under-tests.md):

| from | to |
|---|---|
| `Dockerfile` | `infra/docker/Dockerfile` |
| `infra/docker-compose.yml`, `infra/docker-compose.prod.yml`, `infra/garage.toml`, `infra/traefik-clientaddr.go`, `infra/traefik-clientaddr.yml`, `infra/traefik-dev/`, `infra/.env.example` | `infra/docker/…` |
| `charts/veyq/` | `infra/kubernetes/charts/veyq/` |
| `tools/Dockerfile` | `infra/kubernetes/tools/Dockerfile` |
| `e2e/` | `tests/e2e/` |
| `infra/e2e/` | `tests/e2e/stack/` |
| `infra/fuzz/` | `tests/fuzz/` |

`infra/README.md` stays. Every reader is re-pointed, all checks below are green, and the docs describe the new shape.

## Decisions this CR takes

1. **One module holds the script-side paths.** `scripts/repo_layout.py` names every moved location once (`ROOT`,
   `DOCKER_DIR`, `APP_DOCKERFILE`, `DEV_COMPOSE`, `PROD_COMPOSE`, `ENV_TEMPLATE`, `CHART_DIR`, `TOOLS_DOCKERFILE`,
   `E2E_DIR`, `E2E_STACK_DIR`, `FUZZ_DIR`, plus `LEGACY_CHART_PATH = "charts/veyq"` for decision 3 of the ADR); the
   scripts import it (they run as `python3 scripts/x.py`, so `scripts/` is on `sys.path`; keep the system Python 3.9
   compatible, `from __future__ import annotations` where a `X | None` appears). Split-form constants
   (`ROOT / "infra" / "fuzz"`) are exactly what a grep misses — after this CR none remain outside that module.
   `validate_guard_gaps.py`'s forgeries keep their own literals where a forgery must spell the path it forges.
2. **`VEYQ_INFRA_DIR` now names the Compose directory** (`…/infra/docker`), and `run_pipeline.py` sets it so. Its
   readers (`run_web_e2e.py`, `run_api_fuzz.py`, `e2e_sweeps.py`) say so in the comment that introduces it.
3. **The non-recursive `INFRA.glob("*.yml")` scans** (`validate_app_volume_ownership.py`,
   `validate_config_secret_persistence.py`) read the Compose files from `repo_layout` explicitly, and still refuse an
   empty set; `validate_release_identity.py`'s `git ls-files infra/` tag scan names the directories it means
   (`infra/`, `tests/e2e/stack/`, `tests/fuzz/`) so its coverage does not quietly change.
4. **The e2e workspace's `npm run check` joins CI's per-surface checks** (`ci.yml` checks crypto, web, admin-web
   today): twelve specs reach out of the tree by `new URL('../../../…', import.meta.url)`, and nothing that runs on
   every commit reads those strings. Add a test in `tests/e2e/` that resolves every such URL in `tests/**/*.spec.ts`
   and asserts the file exists — bitten by reverting one spec's extra `../`.

## Phases

- [x] **1 readers that work on both layouts** — on the untouched tree, in a commit of its own
  (`changes/README.md` § *A move re-points its readers first*):
  `chart_upgrade_check.py` finds the predecessor's chart at the new path, else at `charts/veyq` (bite: point the
  lookup at the old path only, the selftest must show it would skip; then restore); `docker-compose.yml` declares
  `name: infra`, and `run_web_e2e.py`'s `DOCKER_NETWORK` reads the declared name (rewrite the comment above it, which
  argues the opposite for a reason that no longer holds); `scripts/repo_layout.py` with today's paths and every
  script switched to it; decision 3's scans. · Done when: every `scripts/validate_*.py` green **including**
  `validate_guard_gaps.py` (guards changed); `chart_upgrade_check_selftest.py`, `instance_backup_selftest.py`,
  `upgrade_proof_selftest.py`, `mutation_meta_check_selftest.py` green; `docker compose -f infra/docker-compose.yml
  config --quiet` green and its `name` is `infra`.
- [ ] **2 the move** — `git mv` per the table, then only the mechanical readers: `repo_layout.py`'s constants; the
  workflows (`ci.yml` matrix `dockerfile:`, the L1 `file:` / `--file`, `helm package`, paths and working directories),
  `.github/dependabot.yml` (`/` → `/infra/docker`, `/tools` → `/infra/kubernetes/tools`, `/infra` → `/infra/docker`,
  `/charts/veyq` → `/infra/kubernetes/charts/veyq`); `launcher/infra/build.gradle.kts` (the main checkout's
  `infra/docker/docker-compose.yml`), `launcher/backend/build.gradle.kts` (`-f infra/docker/Dockerfile`);
  root `package.json` workspaces (`tests/e2e`) and a regenerated `package-lock.json` (follow `package.json`'s
  `//lockfile` traps; `git diff package-lock.json` must show only the workspace's path moving);
  `.gitignore` (`infra/docker/.env`; `infra/backups` stays unless the backup root moves — it does not);
  `tests/e2e/stack/docker-compose.e2e.yml`'s container paths (`working_dir: /repo/tests/e2e`,
  `/repo/tests/e2e/stack/nss_trust_root.py`); `tests/fuzz/schemathesis.toml`'s `hooks = "tests/fuzz/hooks.py"`
  (root-relative, resolved under `-w /repo`); the twelve out-of-tree spec URLs gain one `../`; the
  `${X:?set X in infra/.env}` messages → `infra/docker/.env`; decision 4's CI step and URL test.
  Not touched: the registry path (`git grep -n 'ghcr.io.*charts/veyq'` — every hit stays).
  · Done when: phase 1's checks again; `npm ci` from the root, then `npm run check` in `packages/crypto`, `web`,
  `admin-web`, `tests/e2e`; `./gradlew check` in `backend/`; `docker compose config --quiet` for the dev file, the prod
  file (with a throwaway `.env` from `.env.example`) and the prod + L1 overlay; `python3 scripts/chart_meta_check.py`;
  `docker buildx build --check -f <each of the three Dockerfiles> .` (lint only, no image); `python3
  scripts/release_assets.py` in whatever dry mode it has, else its selftest. **No** L1, web-e2e, api-fuzz or races
  run — those are the owner's V1 heavy stage (owner 2026-10-19), and the owner runs the full suite after this CR.
- [ ] **3 docs and prose** — `CLAUDE.md` § Repository shape (and its mentions of `charts/veyq/`, `e2e/`),
  `infra/README.md` (its `-f infra/docker-compose.yml` commands, `traefik-dev/` link, the launcher section),
  root `README.md`, `backend/CLAUDE.md`, the site's Run and Security pages that give a repo path or a
  `github.com/…/blob/main/<path>` link, ADR text that *instructs or links* (0057, 0063, 0064, 0067, 0090, 0091, 0096,
  0098; not the ledgers of 0092, 0094, 0096 § 28, 0098 — ADR 0106 decision 4), `docs/coverage/**` prose, the
  `docs/inbox.md` rows, `changes/README.md` and `cr-volume-measurement-cax.md`, and prose comments in code, configs
  and scripts that name a moved path. · Done when: the locator below lists only registry paths, ledger lines and this
  CR; every `scripts/validate_*.py` green; `npm run check` in `site/` green.

### Locator (run it, do not trust a count)

```bash
git grep -n -E '(^|[^a-z/._-])(charts/veyq|tools/Dockerfile|infra/(docker-compose|garage\.toml|traefik|e2e|fuzz|\.env)|e2e/)' -- ':!changes/cr-repo-layout-infra-and-tests.md'
git grep -n -E '"infra"\s*/|"charts"\s*/|"e2e"\s*/|"fuzz"|ROOT / "Dockerfile"|"Dockerfile"' -- scripts launcher
git grep -n -E "new URL\('(\.\./)+" -- e2e tests
```

The first misses split constants (the second finds them) and relative specifiers (the third). A grep hit for
`e2e/` inside `web/src/**/*.e2e.test.ts` names or `@napi-rs/canvas`'s `"e2e/*"` in the lockfile is not a reader.

### Traps already measured (2026-10-20, inventory over `e1fa53d3`)

- `chart_upgrade_check.py` with one `CHART_PATH` for both trees passes over nothing (ADR 0106 decision 3).
- Compose's `.env` is read from the project directory: `infra/docker/.env` after the move.
- `release_assets.py` collects `garage.toml` and the traefik files from `COMPOSE.parent` — they move together, keep it so.
- `validate_npm_installs_gated.py` requires `ci.yml` to spell each Dockerfile path exactly; `validate_release_identity.py`
  reads the matrix `dockerfile:` from `ci.yml` — move both together.
- `validate_doc_links.py` scans `docs/`, `changes/`, `site/` only: root `README.md`, `CLAUDE.md`, `infra/README.md`,
  `backend/CLAUDE.md` have no guard — check their links by hand (`python3 - <<…` resolving each relative link).
- The worktree's launcher and `run_pipeline.py` resolve the **main checkout's** Compose file; until this lands on main
  they point at a file main does not have. Do not run `infra:*` launcher tasks or the pipeline from the worktree.

## Definition of done

Every phase's Done-when; the locator clean; `CLAUDE.md`'s shape matches `ls`; the owner told the two machine-local
steps after the merge: move `infra/.env` to `infra/docker/.env`, and bring the dev stack up once from the new file
(`./gradlew infra:up`, same project, same volumes) and re-sync IntelliJ's Gradle and npm projects.

## Retrospective

