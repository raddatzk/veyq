---
id: cr-root-gradle-launcher
title: A root Gradle build that triggers the existing dev, build and image mechanisms, replacing the IntelliJ run configurations
status: in-progress
satisfies: []
adrs: ["0057"]
invariants:
  - "Self-hostable by default: the root build fetches nothing but its pinned Gradle distribution; every task runs a command the repo already documents"
  - "No deprecated dependencies: the root build applies no plugin and resolves no dependency, so it carries nothing to keep alive"
---

## Goal

One Gradle entry point at the repo root from which a developer starts the dev infra, the backend, the dev servers,
the code generation, the local principal, the builds and the two images — from the terminal (`./gradlew web:run`)
or from IntelliJ's Gradle tool window — so the seven `.run/*.xml` configurations can go. Tasks are named
`<surface>:<verb>` (`infra:up`, `web:bootstrap`, `backend:run`, `admin:build`). The root build is a **launcher, not an
orchestrator**: every task triggers a mechanism that exists today (an npm script, a `docker compose` line, the
backend's own `./gradlew`), unchanged. No surface's build moves into it, and nothing aggregates the surfaces' checks
— each still proves itself with its own `npm run check` / `./gradlew check`, which is what the root `package.json`'s
`"//"` note promises and keeps promising. Inbox #455.

## Context & constraints

- **`<surface>:<verb>` means one Gradle project per surface, and those projects live under `launcher/`.** A Gradle
  task name cannot contain `:`; `web:run` is task `run` of project `:web`. The projects are `:infra`, `:backend`,
  `:web`, `:admin`, `:site`, each with `projectDir = launcher/<name>` and its tasks in
  `launcher/<name>/build.gradle.kts`; an `Exec` runs in the surface's real directory (`rootDir.resolve("web")`).
  Measured 2026-10-16 on a scratch build with Gradle 9.5.1: `./gradlew web:bootstrap infra:up` runs exactly those
  two, the web task's working directory is the surface's, and a bare `./gradlew bootstrap` runs the task in every
  project that has one (alphabetically by path). Why not `projectDir = web/` etc.: `backend/` cannot be one (below),
  a Gradle file inside an npm package lands in its Docker build context (`COPY web/ ./`), and an IntelliJ Gradle
  module whose content root is `web/` or `backend/` competes with the module IntelliJ already has there. One
  directory for all five keeps the launcher in one place and out of every surface.
- **The backend is triggered, never included.** `includeBuild("backend")` was the obvious shape and is refused:
  Gradle's dependency-verification docs, fetched 2026-10-16 (docs.gradle.org/current/userguide/dependency_verification.html):
  *"If an included build has its own verification metadata, that configuration is ignored in favor of the current
  build's settings."* Including `backend/` would verify its ~1000 components against the root's file, not
  `backend/gradle/verification-metadata.xml` (ADR 0057 §6, `backend/CLAUDE.md` § Prove your work) — a second copy
  that drifts, or the backend's verification silently replaced. So `backend:run` / `backend:build` run
  `backend/gradlew` as a process, and `backend/` stays a build of its own: Dockerfile, CI, its wrapper and its
  metadata untouched. Cost, accepted: the backend's own projects are not in the root build's task tree (IntelliJ keeps
  `backend/` linked as its own Gradle project, as today), and the debugger attaches to `quarkusDev`'s debug port
  instead of through the run configuration — phase 1 measures the port.
- **The root build is dependency-free, and the existing guard holds it there.** `validate_build_input_pins.py`
  check 4 requires every directory of a tracked `gradlew` to carry `gradle/verification-metadata.xml` with
  `<verify-metadata>true</verify-metadata>`; check 3 a `distributionSha256Sum` in every wrapper properties file;
  check 5 every tracked `gradle-wrapper.jar` hashing to a line of `scripts/gradle_wrapper_jar.sha256`. The root gets
  the backend's wrapper (same Gradle 9.5.1 distribution and sum, same 8.14.3 JAR — no new pin line) and an
  **empty** verification file: a plugin or dependency added to the root later fails its build until someone writes
  metadata for it, which is the moment to ask whether the launcher is turning into an orchestrator. No
  `buildSrc`/`build-logic` either — `kotlin-dsl` resolves dependencies; the tasks are plain `Exec`.
- **No env in the launcher: the dev defaults are made to agree instead.** Today three run configurations set env
  because the defaults disagree with how the dev instance is browsed: `Run Frontend` sets `VITE_PORT=8081` over
  web's default 5173, and `Run Backend` sets `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT=http://veyq.localhost:8081` over
  `%dev`'s `http://127.0.0.1:5173` — and the two must agree by hand (the `Run Backend` comment says so; a mismatch
  is a presigned URL that fails in the browser with nothing pointing at the cause). So the defaults move to the
  instance everyone browses: `web/vite.config.ts` defaults the port to **8081**, and
  `backend/src/main/resources/application-dev.properties` sets `veyq.bundled-store.public-endpoint=http://veyq.localhost:8081`
  (owner, 2026-10-16). `Run Admin`'s two env values already equal `admin-web/vite.config.ts`' defaults (5174,
  `http://127.0.0.1:8090`) and are dropped. Every override keeps working as today — `VITE_PORT`,
  `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT`, `VITE_ADMIN_PORT`, `VEYQ_API_TARGET` are still read from the environment —
  and a bare `npm run dev` / `quarkusDev` now gives the same instance the launcher does. What else names 5173 and
  moves with it: `web/CLAUDE.md` (the multi-tenant browse examples, lines ~90–94), the `application-dev.properties`
  comment above the key, the `application-e2e.properties` comment that quotes `%dev`'s value (line ~44). Not
  moved: `.claude/launch.json` (the agent's preview tooling) sets `VITE_PORT=5173` explicitly and is the owner's
  machine-local file; it then browses an origin `%dev` no longer signs for, which the owner decides about.
- **Ports are Gradle properties, for a second instance from a worktree (owner, 2026-10-16) — and an absent property
  sets nothing.** Five optional properties: `veyq.webPort`, `veyq.adminPort`, `veyq.backendPort`,
  `veyq.backendAdminPort`, `veyq.debugPort`. A task sets an env variable **only** for a property that is given, so the
  defaults stay the surfaces' own (previous bullet) and the launcher holds no second copy of them. A given property
  sets every value derived from it, never just one, because the pairs that today must agree by hand are exactly what
  a second instance gets wrong:

  | Property | Sets (on which tasks) |
  |---|---|
  | `veyq.webPort` | `VITE_PORT` (`web:run`); `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT=http://veyq.localhost:<webPort>` (`backend:run`) |
  | `veyq.adminPort` | `VITE_ADMIN_PORT` (`admin:run`) |
  | `veyq.backendPort` | `QUARKUS_HTTP_PORT` (`backend:run`); `VEYQ_API_TARGET=http://127.0.0.1:<backendPort>` (`web:run`); `VEYQ_BASE` (`backend:seed`) |
  | `veyq.backendAdminPort` | `VEYQ_ADMIN_CHANNEL_PORT` (`backend:run`, key `veyq.admin-channel.port`); `VEYQ_API_TARGET` (`admin:run`); `VEYQ_ADMIN_BASE` (`backend:seed`) |
  | `veyq.debugPort` | `quarkusDev`'s `-Ddebug=<port>` (`backend:run`) |

  Where they live per worktree: an untracked `local.properties` at the checkout's root (gitignored), which
  `settings.gradle.kts` loads when it exists; `-P` on the command line wins over it. Not the tracked root
  `gradle.properties` (a port there is a diff in every worktree) and not `~/.gradle/gradle.properties` (one value
  for every checkout on the machine, the opposite of the point). IntelliJ's Gradle tool window runs a task without
  arguments, so the file is what makes a worktree's ports stick there. The env keys are the ones the code reads
  today (`backend/core/src/main/resources/application.properties` for `veyq.admin-channel.port`, Quarkus' own
  `quarkus.http.port`, `web/src/test/e2eBootstrap.ts` for `VEYQ_BASE`/`VEYQ_ADMIN_BASE`); phase 2 proves each by a
  listener on the overridden port, not by reading the mapping.
  **What ports do not separate, and this CR does not either:** the dev infra is one per machine
  (`infra/docker-compose.yml` names its containers `veyq-mariadb`, `veyq-garage`, …), so a worktree's backend runs
  on the **same database and bucket** as the main checkout's. A branch's new migration is then applied to the
  shared dev database, and the main checkout's backend refuses an unknown version or runs against a schema it does
  not know. A separate database and bucket per instance is its own topic (inbox #456), not a port.
- **Long-running tasks are the risk.** Stopping a Gradle `Exec` ends its process, but `npm run dev` is npm → sh →
  node, and the Vite node process can outlive it on the port. Phase 2 bites this before any `.run/` file is deleted;
  if npm orphans, the task starts Vite's own bin with `node` instead. The same check covers `backend:run`
  (outer Gradle → `backend/gradlew` → daemon → `quarkusDev` JVM). The verbs are shared across projects, so a bare
  `./gradlew run` selects every surface's `run`: sequentially it blocks on the first one, with `--parallel` it would
  start them all — phase 2 states which, measured, in `infra/README.md`; it is not designed for in this CR.
- **"Deliver locally" is the two images, not a running production stack.** `backend:image` and `admin:image` run
  `docker build` for `Dockerfile` and `admin-web/Dockerfile`, both with context `.` exactly as `container.yml` does
  (its matrix, `context: .`). The root `Dockerfile` bakes web's SPA into the backend's fast-jar, so `backend:image` is
  the app image — `web` has no image of its own. Booting the production recipe locally needs TLS and a domain; that
  is `cr-stack-e2e`'s `run_stack_e2e.py` (planned), and a task triggering it is a follow-up once that exists.
- **`.run/` stays as a directory**: `.run/.env` and `.run/.env.example` are inputs of `npm run bootstrap:user` and of
  web's dev login (`web/vite.config.ts`, `web/CLAUDE.md`), not run configurations. Only the `*.xml` go.
- **No `npm install` beyond what runs today.** `web:bootstrap` is `Bootstrap Frontend`'s `npm install` in `web/`,
  the same command (not the CI gate `npm_ci_no_deprecated.py`, which is `npm ci` and red on a deprecation warning — a
  local install that refuses on a new upstream deprecation is a different behaviour, not a trigger of the existing
  one). `site:bootstrap` is `npm install` in `site/`, which sits outside the workspace and installs on its own.
  `validate_npm_installs_gated.py` reads workflows and Dockerfiles only, so it is not touched.
- *Machine hint, measured 2026-10-16 on the owner's Mac:* the `node` on `PATH` is `/opt/homebrew/bin/node`
  v23.11.0, outside the root `package.json`'s `engines` range, while the npm configurations run IntelliJ's
  *project* interpreter, which may be another node. An `Exec` from the Gradle daemon uses the daemon's `PATH`.
  Phase 1 prints which node each task runs; if it differs from the interpreter the npm configurations used, that is
  reported to the owner, not papered over in the build.

### Inventory — the locators

| What | Locator |
|---|---|
| What each configuration runs today (the trigger spec) | `grep -n 'SCRIPT_TEXT\|<script value\|<command value\|taskNames' -A2 .run/*.xml` |
| The env each configuration sets | `grep -n '<env name\|<entry key' .run/*.xml` |
| The dev defaults that move | `grep -n 'VITE_PORT' web/vite.config.ts`; `grep -n public-endpoint backend/src/main/resources/application-dev.properties` |
| Everything else naming 5173 | `grep -rn 5173 --include='*.py' --include='*.ts' --include='*.kts' --include='*.properties' --include='*.md' --include='*.json' . \| grep -v node_modules` |
| The npm scripts the tasks call | `python3 -c "import json;[print(p, list(json.load(open(p+'/package.json'))['scripts'])) for p in ('web','admin-web','site')]"` |
| Gradle build-input guard (checks 3–5) | `sed -n 1,30p scripts/validate_build_input_pins.py` |
| Everything that names a run configuration | `grep -rln -E 'Run Backend\|Run Frontend\|Run Admin\|Bootstrap (DB\|User\|Frontend\|Admin)' --include='*.md' --include='*.py' --include='*.ts' --include='*.kts' . \| grep -v node_modules` → `infra/README.md`, `web/CLAUDE.md`, `admin-web/CLAUDE.md`, `backend/buildSrc/src/main/kotlin/veyq.module.gradle.kts:68`, `scripts/validate_run_configs.py`, `scripts/validate_guard_gaps.py` (`forge_run_config_env_path_into_the_void`) |
| Image build context | `grep -n 'dockerfile:\|context:' .github/workflows/container.yml` |

### The tasks (each one is the existing command, nothing more)

| Task | Runs (in) | Replaces |
|---|---|---|
| `infra:up` | `docker compose -f infra/docker-compose.yml --profile s3 --profile mail up -d`, then the same with `--wait mariadb` (repo root) | Bootstrap DB + Garage |
| `infra:down` | the same file and profiles, `down` | — (infra/README) |
| `infra:wipe` | `down -v` — destructive, its description says so | — (infra/README cold start) |
| `web:bootstrap` | `npm install` (`web/`; its `prepare` generates the API client) | Bootstrap Frontend (generate OpenAPI) |
| `admin:bootstrap` | `npm run generate:api` (`admin-web/`) | Bootstrap Admin (generate OpenAPI) |
| `site:bootstrap` | `npm install` (`site/`) | — |
| `backend:run` | `./gradlew quarkusDev` (`backend/`) | Run Backend |
| `web:run` | `npm run dev` (`web/`) | Run Frontend |
| `admin:run` | `npm run dev` (`admin-web/`) | Run Admin |
| `site:run` | `npm run dev` (`site/`) | — |
| `backend:seed` | `npm run bootstrap:user` (`web/` — the script lives there; what it seeds is the running backend's instance) | Bootstrap User |
| `backend:build` | `./gradlew quarkusBuild` (`backend/`) | — |
| `web:build`, `admin:build`, `site:build` | `npm run build` in each | — |
| `backend:image` | `docker build -f Dockerfile -t veyq:local .` (repo root) | — |
| `admin:image` | `docker build -f admin-web/Dockerfile -t veyq-admin:local .` (repo root) | — |

No `dependsOn` between them that the existing flow does not have: `backend:run` does not start the infra (starting
the backend against a stopped database is today's behaviour, and a dependency would make every backend restart touch
compose). The order stays documented, in `infra/README.md`, now as task names. No task is named `check`, `test` or
`clean`: a bare `./gradlew check` would then select every surface's and aggregate exactly what the launcher must not.

## Phases

Done when, per phase: the tasks it adds were each run once and their output read; the bites below made red once;
`python3 scripts/validate_build_input_pins.py` and the trust-chain green.

- [x] 1 **The root build, the dev defaults and the finite tasks.** Root `settings.gradle.kts`
      (`rootProject.name = "veyq"`, the five `include`s with their `launcher/<name>` directories, the optional
      `local.properties` load — and `local.properties` added to `.gitignore`, which does not name it today), the wrapper copied
      from `backend/` (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`), an empty `gradle/verification-metadata.xml`
      (`<verify-metadata>true</verify-metadata>`, no components, no trust rules), `launcher/<name>/build.gradle.kts`
      with every task above except the three Vite `run`s. The dev defaults move (§ Context: web's port 8081, `%dev`'s
      public endpoint, the comments and `web/CLAUDE.md` that quote them); `npm run check` in `web/` and
      `./gradlew check` in `backend/` green after. Measure and write into the task's description: which `node` the
      `Exec` runs (hint above), and the port `quarkusDev` opens for a debugger. Bites: a plugin added to a launcher
      project's `plugins {}` → the build red on verification; a byte changed in the root `gradle-wrapper.jar` →
      `validate_build_input_pins.py` red; the root `distributionSha256Sum` removed → red; a task named `check` added
      to two launcher projects → `./gradlew check` runs both (the reason for the naming rule, shown once).
      `infra:wipe` is run once by the owner, not by an agent (the classifier blocks DB wipes).
- [x] 2 **The dev servers, and that stopping them stops them.** `web:run`, `admin:run`, `site:run`. For each of those
      and `backend:run`: start from IntelliJ's Gradle tool window, stop it there, then `lsof -nP -iTCP:<port>
      -sTCP:LISTEN` is empty (8081, 5174, the site's dev port, 8080 and 8090); the same with Ctrl-C from a terminal. An
      orphan is the bite this phase exists for — if `npm run dev` leaves one, start the dev server's bin with `node`
      directly and run it again. Then: with no env anywhere, `infra:up` → `backend:run` → `web:run` → `backend:seed`,
      log in at `veyq.localhost:8081` and upload one image (the presigned PUT reaches the store — the proof that the
      moved defaults agree). Record what a bare `./gradlew run` does. **A second instance:** from another worktree,
      a `local.properties` with all five ports moved (e.g. 8082, 5175, 8180, 8190, 5006); its `backend:run`,
      `web:run`, `admin:run` listen on exactly those (`lsof`), the main instance keeps its own, and an upload in the
      second instance's browser reaches the store (its endpoint followed its web port). Stopping one leaves the
      other's ports listening. Bite: `veyq.webPort` given but the endpoint derivation removed → the second instance's
      upload fails — the reason a property sets every derived value.
- [x] 3 **The run configurations go.** Delete each `.run/*.xml` whose replacement passed phase 2 (all seven, unless a
      dev server orphaned and could not be fixed — then it stays, and so does `validate_run_configs.py` for it). With
      no XML left: delete `scripts/validate_run_configs.py` and `validate_guard_gaps.py`'s
      `forge_run_config_env_path_into_the_void` (and its registration). Docs: `infra/README.md` (the dev stack and the
      seeding section speak task names; the cold-start order as `infra:up` → `backend:run` → `backend:seed`; the task
      list, from `./gradlew tasks --group veyq`), `web/CLAUDE.md` and `admin-web/CLAUDE.md` (the IntelliJ configuration
      names), the `veyq.module.gradle.kts:68` comment (`Run Backend` → the root `backend:run`, which still runs from
      `backend/`), and the root `CLAUDE.md` § Repository shape gains `launcher/`. Bite: `grep -rn 'Run Backend\|Run
      Frontend\|Run Admin\|Bootstrap DB\|Bootstrap User\|Bootstrap Frontend\|Bootstrap Admin' --include='*.md'
      --include='*.py' --include='*.ts' --include='*.kts' .` (node_modules excluded) returns nothing.

## As built (2026-10-16) — what the phases measured, and where it went differently

- **Phase 1.** Every finite task ran green once (`infra:up` a no-op on the running infra, the three `bootstrap`s —
  the lockfile unchanged —, the four `build`s, both `image`s). The `node` every `Exec` runs is
  `/opt/homebrew/bin/node` 23.11.0 (`EBADENGINE` warnings), the only node on the machine, so the interpreter the
  npm configurations used was the same one: no behaviour change, and the engines mismatch predates this CR. Bites:
  a plugin in a launcher project → `Dependency verification failed … com.github.node-gradle.node` (on a scratch copy);
  a byte appended to the root wrapper JAR, the root `distributionSha256Sum` removed, the root `verify-metadata` set to
  `false`, the root metadata file untracked → each red in `validate_build_input_pins.py`, green restored. The
  check-naming bite was shown by the scratch build of § Context (a bare verb selects every project's task), not
  re-run. **`validate_build_input_pins.py` needed two fixes the CR did not foresee:** check 4 formatted the metadata
  path as `f"{root}/gradle/…"`, which for a `gradlew` at the repo root is `./gradle/…` and never matches `git ls-files`
  (red on a tracked file — a root wrapper had never existed); and it refused an empty component list, which for a
  build that resolves nothing is the strictest file there is (the plugin bite above is the proof).
- **Not in the plan: `infra:*` runs the MAIN checkout's compose file.** From a worktree, its own copy is the same
  compose project (`infra`) with other mount sources; `up --dry-run` with it answered *Recreate* for `veyq-garage`
  and `veyq-traefik`, with the main checkout's file *Running* for all four. The task resolves the main checkout from
  `git rev-parse --git-common-dir`.
- **Phase 2.** Ctrl-C through a pseudo-terminal on `web:run`, `admin:run`, `site:run`, `backend:run` (this worktree's
  `local.properties`: 8082, 5175, 4322, 8180, 8190, 5006) left no port listening; SIGTERM to the Gradle client did the
  same for `web:run`. npm did NOT orphan Vite, so the `node`-direct fallback was not needed. **`site:run` did detach at
  first:** Astro 7 backgrounds `astro dev` when `am-i-vibing` detects an AI agent in the environment, so the task
  ended while the server held the port; it now passes `--ignore-lock` (Astro's documented one-off foreground server).
  `backend:run` on the moved ports listened on 8180, 8190 and 5006, and its process carried
  `VEYQ_BUNDLED_STORE_PUBLIC_ENDPOINT=http://veyq.localhost:8082` — the bundled store reads that hint at runtime
  (`BundledStoreHint`), not from the database, so the derivation reaches the signer. `backend:run web:run --parallel`
  ran both. The main checkout's servers (8081, 5174, 4321) kept listening throughout.
- **Not proven, and why:** the upload in a browser. The shared dev database is on a baseline older than `main`'s
  (`3ff35070` renamed the counters to revisions 11 hours earlier): `Unknown column 'meta_revision'`, `Table
  'veyq.veyq_capability_switch' doesn't exist` — the pre-release baseline rule's "every existing database is then
  recreated" (`backend/CLAUDE.md` § Migrations) has not happened on it yet, and a wipe is the owner's. Flyway applied
  nothing (`Schema veyq is up to date`; `repair-at-start` re-wrote the checksums, as every `%dev` start does). Also not
  exercised: starting and stopping from IntelliJ's Gradle tool window (a terminal Ctrl-C and a client SIGTERM are,
  and both cancel the build the same way).
- **Phase 3.** All seven `.run/*.xml`, `scripts/validate_run_configs.py` and guard-gap case 15 are gone; `.run/` keeps
  `.env` and `.env.example`. The run-configuration names remain only in this CR and in two closed inbox rows.
  `validate_release_identity.py` refused `veyq:local` written in `infra/README.md` (its tag scan covers `infra/`, where
  a tag is one an operator would pin); the README says "tagged `local`" and the full names live in the task
  descriptions. `npm run check` in `web/` green; the trust-chain green. `./gradlew check` in `backend/` was not
  run: no test reads `%dev` (`grep` over `backend/**/*.kt` finds no `application-dev` or port reader), and
  `backend:run` booted the changed `application-dev.properties` live.

## Definition of done

From a clean checkout with the dev infra down and no env set: `./gradlew infra:up`, `backend:run`, `web:run`,
`admin:run`, `backend:seed` bring up a working dev instance (log in at `veyq.localhost:8081`, upload one image);
`./gradlew bootstrap` and `./gradlew build` run every surface's; `backend:image` and `admin:image` build both images;
every stop leaves its port free; every bite above was red once; the trust-chain is green. No `satisfies:` (tooling,
no use-case), so no coverage YAML. No docs-site page is affected: the Run area is for Server Owners and documents the
production recipe, never the dev stack. Follow-up, not this CR: a task booting the production recipe once
`cr-stack-e2e`'s `run_stack_e2e.py` exists.

## Retrospective

(filled at close)
