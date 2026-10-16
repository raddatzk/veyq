---
id: cr-stack-e2e
title: The release candidate booted from its own recipe and driven in a real browser, gating the publish
status: planned
satisfies: ["uc.admin.setup-server", "uc.admin.configure-object-store", "uc.admin.switch-capabilities", "cc.security.wire-assertion"]
adrs: ["0090", "0057", "0053", "0040", "0071", "0065"]
invariants:
  - "Operator-blind E2EE: every value a scenario types is a canary the run sweeps the database, the bucket and every log for (ADR 0053)"
  - "Self-hostable by default: the browser may reach the instance only; the run needs no internet once its images and browser are pulled"
  - "No deprecated dependencies: @playwright/test and the Pebble image go through the same gates as every other dependency"
---

## Goal

A CI job that boots the two candidate images with the compose recipe taken **out of the app image**, gets a real
ACME certificate from a Pebble, drives admin-web and web in Chromium through first run and a Memory round trip,
sweeps the stack for every typed value, and gates `publish` on the exact `build-<sha>-<arch>` bytes it then signs.
[ADR 0090](../docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md) holds every decision, its
rejected alternatives and the four refine answers; this CR orders the work. Inbox #448; #447 is its phase 5.

**Entry condition met:** ADR 0090 was accepted on 2026-10-16. Implementation still waits for the owner's go on this CR.

## Context & constraints

- **There is no remote.** No phase can be proven in CI; each phase's proof is a **local** run of
  `scripts/run_stack_e2e.py` against locally built images (`docker build` of both Dockerfiles), and its log is read.
  The CI job is written in phase 4 and first proven by the first push — after which `stack` is admitted (ADR 0040).
- **The draft is in the tree, uncommitted**, on the worktree branch `claude/e2e-tests-ci-integration-c53eb0`:
  `e2e/`, `infra/e2e/docker-compose.e2e.yml`, `scripts/run_stack_e2e.py`, the `e2e` job in `container.yml`. It is
  the shape, not the build: `TODO(implement)` marks every hole, and the web/ locators are placeholders.
- [`scripts/run_web_e2e.py`](../scripts/run_web_e2e.py) owns the ADR 0053 sweeps, bound to the dev stack's container
  names and root credential; phase 3 moves them, and `run_web_e2e.py` must stay byte-for-byte in behaviour (its own
  CI job, its `--upgrade` proof, `validate_e2e_discovery.py` / `validate_e2e_canaries.py` import from it).
- Guards that anchor on what this CR touches — `changes/README.md` § *before reshaping a symbol, grep `scripts/`*:
  `validate_release_identity.py` (registry writes only on a dispatch: an unpinned `push:` on a build step is read as a
  push — measured on the draft, fixed with `push: false`), `validate_image_pins.py` (Pebble's digest; it scans only
  **tracked** files, so the draft's `@sha256:TODO` is green until committed), `validate_npm_installs_gated.py` (the
  new workspace member installs through `npm_ci_no_deprecated.py`), `validate_action_pins.py`,
  `validate_compose_posture.py` (production file only; the overlay keeps the posture by hand — phase 1 decides whether
  the guard reads it too), `validate_coverage.py` (`SURFACES`, `FILE_ROOTS`, `ADMITTED`).
- *Machine hints, for the CR's planned shape:* a local run needs host ports 80 and 443 free (the recipe publishes
  Traefik there); its admin port is 18081, never the developer's 8081; its compose project is `veyq-e2e-<stamp>`, so
  it cannot recreate a dev or production container. Re-derive these against what is built.

### Inventory — the locators

| What | Locator |
|---|---|
| What the recipe asks of an `.env` | `grep -o '\${[A-Z_]*' infra/.env.example infra/docker-compose.prod.yml \| sort -u` |
| admin-web strings the setup project clicks | `grep -n '"title"\|"submit"\|"kitProceed"\|"sourceBundled"\|"check"\|"create"' admin-web/src/i18n/index.ts` |
| The sweeps to move | `grep -n "^def sweep\|^def token_needles\|^def db_token_findings" scripts/run_web_e2e.py` |
| Who imports from `run_web_e2e.py` | `grep -rln "run_web_e2e" scripts .github` |
| Every gate `publish` waits on | `grep -n "needs\.\|needs:" .github/workflows/container.yml` |
| Coverage files that would name `stack` | `grep -rln "off-state-in-a-browser\|setup-server\|configure-object-store" docs/coverage` |

## Phases

Done when, per phase: the phase's local run is green and its log read; the guards in § Context green; the
trust-chain green. Each phase names its bites — the case that must make the new check fail.

- [ ] 1 **A bare boot.** `run_stack_e2e.py` extracts the recipe from a locally built image, writes the `.env` from
      the image's template, lays the Pebble overlay over it (Pebble pinned by digest), brings it up with
      `--wait`, installs Pebble's root into an NSS db, asserts the certificate served on `https://veyq.test` chains to
      it, writes `compose.log`, tears down with `down -v`. No browser yet. Bites: `/acme` mounted read-only (Traefik's
      silent failure) → red at the certificate assertion; a variable added to `.env.example` and not filled → red at
      `up` naming it; the run's project never touches a container of another project (`docker ps` before and after).
- [ ] 2 **First run in a browser.** `e2e/` joins the npm workspace (its own `npm run check`), Playwright pinned;
      `first-run.setup.ts` through to a created tenant and its invite link; `confineToInstance` on every context;
      the Secret Key read back from the kit. Bites: a renamed admin-web label → red naming the locator; a page
      request to another origin (injected `fetch('https://example.org')`) → red listing it; the HIBP stub removed →
      red, the run has no route out. Browser pass: this phase IS one — the trace of a green run is attached.
- [ ] 3 **The Memory round trip, swept.** The sweeps move into `scripts/e2e_sweeps.py`, parameterised by compose
      project, imported by both orchestrators; `run_web_e2e.py`'s own run is unchanged (its CI job's steps, run locally,
      green). `memory-roundtrip.spec.ts` with real web/ locators; every typed value through `recordTyped`; the run
      refuses a green with zero executed tests (junit.xml). Bites: a canary written into a container log by hand →
      red naming canary and log; an empty `--grep` → red, not green.
- [ ] 4 **CI.** The `e2e` job in `container.yml` per architecture (dry run: both images rebuilt from `build`'s cache,
      smoke tier; dispatch: both pulled by digest, full tier); `publish` needs it and requires its `success`; the
      gate promoted to a guard — `validate_release_identity.py` asserts `publish` waits on `e2e` and on its success
      (bite: drop the `needs` → red). Coverage: `stack` added to `SURFACES` and `FILE_ROOTS` (→ `e2e/`), not to
      `ADMITTED`; the served use-cases gain a `stack` gap "awaiting the first CI run (no remote)". Proof: the
      workflow's steps run locally in order; the CI run itself waits for the remote.
      **Runtime is budgeted, not hoped for.** The orchestrator prints each stage's wall time (pull or rebuild, browser
      install, boot to `--wait`, each Playwright project, sweeps, teardown) into its summary; the job carries a
      `timeout-minutes` set from the measured local run; the browser install is cached by Playwright's version. The
      budget at refine (estimated, not measured — no CI run exists): smoke **~6–10 min** per architecture, the full
      tier growing per scenario; the first CI run's numbers replace it in ADR 0090 § Consequences. A tier that grows
      past its budget is split into shards with a stack each, never made faster by weakening what ships (no lowered
      Argon2 parameters, no skipped TLS).
- [ ] 5 **Capability off states (inbox #447).** `capability-off-states.spec.ts` for the four ids, through admin-web's
      switch and back, the `unlock.deviceHeldBack` hint and no DELETE/POST on `/api/account/wraps`. Its coverage ref
      lands in `uc.admin.switch-capabilities.yaml` under `stack.e2e`, and the `off-state-in-a-browser` gap names this
      spec until `stack` is admitted.

## Definition of done

A local run of `scripts/run_stack_e2e.py --tier full` against both locally built images is green, with every bite
above made red once; `container.yml` gates `publish` on `e2e`; the trust-chain is green; ADR 0090's Status says what
is built and that the CI run awaits the remote; the first-run Run page (`site/src/content/docs/run/first-run.mdx`)
drops its "not exercised on a running instance" note in favour of naming this suite. Follow-up, not this CR: the
chart on `kind` (ADR 0090 answer 3).

## Retrospective

(filled at close)
