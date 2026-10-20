---
id: cr-repo-layout-src
title: Stage 2 of the repository layout — the code surfaces under src/
status: in-progress
satisfies: []
adrs: ["0106", "0079", "0065", "0045", "0090", "0091"]
invariants:
  - nothing but paths changes - no behaviour, no image content beyond its build-stage layout, no test semantics
  - Flyway migrations move byte-identical (their checksums are content-only; applied_migrations.sha256 changes its path prefix only)
  - a guard that loses its subject fails; none may scan nothing and say OK
  - a measurement record is not rewritten (ADR 0106 decision 4); conformance-pinned files (contracts/crypto/vectors/v1.json) are not edited
  - the owner's running dev processes, containers and main checkout are not touched by the implementer
---

## Goal

Build [ADR 0106](../docs/decisions/0106-deployment-under-infra-tests-under-tests.md) decisions 5–7:
`backend/` → `src/backend/`, `web/` → `src/web/`, `admin-web/` → `src/admin-web/`, `packages/crypto/` →
`src/packages/crypto/`. Every reader re-pointed, every guard still judging what it judged, the docs describing the
new shape.

## Decisions this CR takes

1. **`scripts/repo_layout.py` names the surfaces** (`SRC`, `BACKEND`, `WEB`, `ADMIN_WEB`, `CRYPTO`, and
   `SURFACES` for the guards that iterate them) plus `LEGACY_SURFACES` (the pre-stage-2 root paths) for decision 7's
   readers. `backend_tree.py`, every guard and runner in the inventory below, and `validate_stylesheet_anchors.py`
   (which derives the surfaces from `ROOT.iterdir()` today) read it. `instance_backup.py` still ships alone and spells
   nothing new; `instance_backup_selftest.py` reads `repo_layout` in a checkout.
2. **Fail closed where a guard goes quiet today** (measured by simulating each surface missing, 2026-10-20):
   `instance_backup_selftest.py`'s three `if not (root / "backend").is_dir(): skip` branches skip only outside a
   checkout (no `.git`), and fail inside one; `validate_doc_citations.py` fails on a configured root that does not
   exist; `validate_web_fragment_paths_via_location.py` and `validate_web_share_bodies_are_drop_pages.py` fail when
   their source root is missing or yields no file; `mutation_meta_check.py` fails per missing project, not only when
   both are missing; `validate_coverage.py`'s `CRYPTO`/`ADMIN_WEB` roots must exist. Each bitten on the untouched
   tree by pointing its root at a missing path (snapshot-and-restore, `changes/README.md`).
3. **Two new guards, for the readers stage 1 found unguarded.**
   - `validate_workflow_paths.py`: every `paths:`/`paths-ignore:` pattern, `working-directory:`, cache `path:` and
     `dockerfile:`/`file:` value in `.github/workflows/*.yml`, and every `directory:` in `.github/dependabot.yml`,
     matches at least one tracked path (globs through `git ls-files` + `fnmatch`). It is red today on
     `codeql.yml`'s `docs/api/**` and `load/**` (0 tracked files): remove those two, then it is green. Bite: point
     one `working-directory` at a missing directory.
   - `validate_doc_links.py` widens its scan to every tracked `*.md` outside `node_modules` (root `README.md`,
     `CLAUDE.md`, `CONTRIBUTING.md`, `SECURITY.md`, `AGENTS.md`, every surface's `CLAUDE.md`, `infra/README.md`). Run
     it on the untouched tree first: what it finds red there is fixed in phase 1, and named in the report.
     Code-comment links stay out of scope (`web/src/…/download.ts:22` is broken today — fix that one by hand).
4. **An image mirrors the checkout under `/repo`** (ADR 0106 decision 6). `infra/docker/Dockerfile`'s frontend stage
   works in `/repo/src/web` with `/repo/contracts`, `/repo/scripts`, `/repo/VERSION`, `/repo/src/packages/crypto` and
   the root manifests; the backend stage in `/repo/src/backend`; later stages copy from those. `src/admin-web/Dockerfile`
   likewise. Mind the name collision: a backend module's own `./src` (`COPY src/backend/core/src ./core/src`) is
   not the repository's `src/`.
5. **Out-of-tree relative paths gain one `../`** — loud everywhere except `src/web/vite.config.ts`'s `../.run/.env`
   (inside a `try/catch` that answers 404: the `?devlogin` auto-unlock would go quiet). That one resolves from the
   repository root found by walking up to `VERSION`, and throws when it is not found.
6. **The upgrade proof stays layout-aware** (ADR 0106 decision 7): `run_web_e2e.py`'s predecessor paths
   (`ls-tree … web/src`, `tree / "backend"`, the jar and generated-client paths, `Build.web`) try the current layout,
   then `LEGACY_SURFACES`; `changed_migrations` compares migrations by file name, not by repository path, so a move is
   not a change. Proven by `upgrade_proof_selftest.py` against the commit before phase 2.
7. **Local secrets stay ignored.** `.gitignore` ignores `src/backend/.env` and keeps `backend/.env` (an existing
   checkout's file is not moved by a merge); `.dockerignore` names the generated clients at their new paths and keeps
   `web/`, `admin-web/`, `backend/` and `packages/` out of the build context entirely (an existing checkout's
   leftover `node_modules/`, `build/` and stale generated clients must not enter an image).

## Phases

- [x] **1 readers on both layouts, guards fail closed, the two new guards** — on the untouched tree, one or more
  commits of their own: decisions 1, 2, 3, 6 (the layout-aware half; the paths still resolve to the root ones), the
  `vite.config.ts` lookup of decision 5 (it works at either depth). · Done when: every `scripts/validate_*.py` green
  including `validate_guard_gaps.py --force`; every `scripts/*_selftest.py` green; each decision-2 bite and each new
  guard's bite observed red, then restored; `npm run check` in `web/`.
- [x] **2 the move** — `git mv` the four trees, then the mechanical readers: `repo_layout.py`; the Dockerfiles
  (decision 4); `.gitignore`, `.dockerignore` (decision 7); root `package.json` workspaces and a regenerated
  `package-lock.json` (the `//lockfile` traps; the diff shows only the workspace paths and their links moving);
  `.github/workflows/*` (`working-directory`, the mutation and CodeQL `paths:` filters, cache paths, the admin
  Dockerfile path), `.github/dependabot.yml`; `launcher/{backend,web,admin}/build.gradle.kts`;
  `.claude/launch.json`; `scripts/applied_migrations.sha256` (prefix only — the hashes must not change; check with
  `cut -c1-64` before and after); `validate_guard_gaps.py`'s forgeries and `mutation_meta_check_selftest.py`'s
  fixtures; the in-tree out-of-tree paths of decision 5 (backend: `build.gradle.kts:68`, `core/build.gradle.kts:33`,
  `buildSrc/…/VeyqOpenApiExtension.kt:39,109,116`, the `File("../…")` and `Paths.get("").resolve("../contracts…")`
  test sites, `application-e2e.properties:77`; web: `package.json`'s `generate:api` and verifier, the
  `scripts/*.mts` preludes, `vite.config.ts:165`, the test sites; admin-web: `package.json`, two tests;
  crypto: three tests); `tests/e2e`'s eleven `exif-sample.jpg` URLs. Every link a guard resolves that the move
  breaks is re-pointed here (stage 1's lesson). Not touched: the migrations' content, `contracts/crypto/vectors/v1.json`.
  · Done when: phase 1's checks again; `python3 scripts/npm_ci_no_deprecated.py`, then `npm run check` in
  `src/packages/crypto`, `src/web`, `src/admin-web`, `tests/e2e`; `./gradlew check` in `src/backend` (background;
  a load-only timeout red is rerun before it counts); `./gradlew help` at the root (the launcher configures);
  **a real image build** of the app image (`docker build -f infra/docker/Dockerfile --target runtime-jvm -t
  veyq:layout-check .`) and the admin image (`docker build -f src/admin-web/Dockerfile -t veyq-admin:layout-check
  .`) — `buildx --check` cannot see a COPY source that is missing or a relative path that broke inside the build; if
  the Docker credential helper stalls a pull, report it rather than wait; remove the two tags afterwards;
  `upgrade_proof_selftest.py`.
- [ ] **3 docs and prose** — `CLAUDE.md` § Repository shape, each surface's `CLAUDE.md` (their `../` links gain one
  level), root `README.md`, `CONTRIBUTING.md`, `infra/README.md`, the site's GitHub `blob/main/…` URLs and prose,
  ADR text that describes where code lives (not the ledgers — ADR 0106 decision 4), `docs/crosscutting`,
  `docs/features`, `docs/coverage` prose (the structured refs are surface-relative and survive), `docs/inbox.md`,
  `changes/README.md`, `contracts/` prose outside the vectors, and prose comments in code and scripts. · Done when:
  the locator lists only ledger lines, the vectors' notes, the legacy constants and this CR; every guard green; `npm
  run check` in `site/`.

### Locator (run it, do not trust a count)

```bash
git grep -n -E '(^|[^a-zA-Z0-9_./-])(backend|web|admin-web|packages/crypto)/' -- ':!changes/cr-repo-layout-src.md' ':!src/' | grep -v -E 'src/(backend|web|admin-web|packages)/'
git grep -n -E '"(backend|web|admin-web|packages)"\s*[/,)]' -- scripts launcher
git grep -n -E "(\.\./)+(contracts|VERSION|scripts|\.run|packages|node_modules)" -- src tests
```

Hazards: `web/` must not match `admin-web/` or `web-e2e`; `../packages/crypto` written from `src/web` or
`src/admin-web` stays correct; the image's `/src` (renamed `/repo` here), a module's `./src` and
`run_stack_e2e.py`'s `--source src/` are not the repository's `src/`.

### Inventory (2026-10-20, over `eaff56f9`; the locator is the authority)

- Hard-coded surface paths in `scripts/`: `backend_tree.py:29` (≈22 guards depend on it, all fail closed),
  `validate_migration_versions.py:59`, `validate_mutation_trigger_paths.py:52`, `mutation_meta_check_backend.py:63`,
  `validate_capabilities.py:107`, `validate_doc_citations.py:76`, `validate_coverage.py:150-152,184`,
  `validate_web_core_imports_no_applications.py:105`, `validate_locale_editor_single_home.py:50`,
  `validate_shell_route_gate.py:43`, `validate_web_contrast.py:48`, `validate_e2e_canaries.py:42`,
  `validate_web_fragment_paths_via_location.py:28`, `validate_web_share_bodies_are_drop_pages.py:30`,
  `mutation_meta_check.py:37`, `run_web_e2e.py:149,194,690,1587,1614,1622-1623`, `run_api_fuzz.py:138`,
  `run_backend_races_on_mariadb.py:68`, `measure_volume.py:75`, `make_exif_fixture.py:24`,
  `upgrade_proof_selftest.py:38`, `instance_backup_selftest.py:299-306,355-359,1186-1189`,
  `validate_stylesheet_anchors.py:131`.
- Substring checks that pass either way: `validate_backend_module_graph.py:564-572` (`"backend/buildSrc" not in
  base`) and `validate_build_input_pins.py:167` (`find("scripts/verify_openapi_generator_jar.mjs")`) — make them
  anchor on the full path from `repo_layout`.
- Unguarded today (decision 3 guards them from now on): `codeql.yml:48,54`, `mutation.yml:22-26,39-43,85-86,103,109`,
  `mutation-backend.yml:114,120`, `dependabot.yml:40,51`.

## Definition of done

Every phase's Done-when; `CLAUDE.md`'s shape matches `ls`; the report names the owner's machine-local steps: stop the
IntelliJ backend and Vite before the merge lands; afterwards move `backend/.env` to `src/backend/.env` (if present),
delete the leftover ignored output under `backend/`, `web/`, `admin-web/`, `packages/` once nothing tracked is left
there, `npm ci` from the root, re-link IntelliJ's backend Gradle project at `src/backend/`, update the IntelliJ run
configurations' working directories.

## Retrospective

