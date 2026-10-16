---
id: cr-stack-e2e
title: Lane L1 — the candidate booted from its own recipe and driven in a real browser, gating the snapshot
status: in-progress
satisfies: ["uc.admin.setup-server", "uc.admin.configure-object-store", "uc.admin.switch-capabilities", "cc.security.wire-assertion"]
adrs: ["0090", "0091", "0057", "0053", "0040", "0071", "0065"]
invariants:
  - "Operator-blind E2EE: every value a scenario types is a canary the run sweeps the database, the bucket and every log for (ADR 0053)"
  - "Self-hostable by default: the browser may reach the instance only; the run needs no internet once its images and browser are pulled"
  - "No deprecated dependencies: @playwright/test and the Pebble image go through the same gates as every other dependency"
---

## Goal

Lane L1 of [ADR 0091](../docs/decisions/0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md): a job
that installs the two candidate images the way a Server Owner does — `veyqctl install` from the candidate's
release assets ([ADR 0096](../docs/decisions/0096-veyqctl-manages-an-instance-from-immutable-releases.md); until
`cr-veyqctl` phase 4 lands, the recipe is extracted from the app image as phase 1 built it) — gets a real ACME
certificate from a Pebble, drives admin-web and web in Chromium through first run, a Memory round trip and the
feature scenarios, sweeps the stack for every typed value, and gates the snapshot on the exact `build-<sha>-<arch>`
bytes it is published on.
[ADR 0090](../docs/decisions/0090-end-to-end-tests-boot-the-release-candidate.md) holds every decision, its
rejected alternatives and the four refine answers; this CR orders the work. Inbox #448; phase 5 automates the browser pass inbox #447 ran by hand on 2026-10-15.

**Entry condition met:** ADR 0090 was accepted on 2026-10-16. The owner gave the go for phase 1 only on 2026-10-16; each later phase waits for its own go.

## Context & constraints

- **ADR 0096 (accepted 2026-10-16) moves the recipe out of the image** into the release's assets and puts
  `veyqctl` in front of every lane (ADR 0090 decision 1, ADR 0091 decision 3). `cr-veyqctl` owns the switch: its
  phase 4 replaces `run_stack_e2e.py`'s recipe-and-`.env` half (`extract()`, `write_env()`) with `veyqctl install
  --source <dir> --unsigned-local`, and hands the change to this CR's owner. Everything after `up` — Pebble, the
  certificate, the browser, the sweeps — stays this CR's, so no phase here changes for it; a phase that edits
  `run_stack_e2e.py` re-reads it on `main` first.
- **ADR 0091 (accepted 2026-10-16) sets the frame:** one pipeline per commit, every stage budgeted, the gate on every
  push, a release that promotes. This CR builds lane L1 only. **It needs the pipeline CR first** (ADR 0091
  § Consequences, the work order: one workflow, the build not waiting for the tests), because phase 4 wires L1 into
  that pipeline; phases 1–3 and 5 do not need it. Promotion, attestations, L2 (inbox #453) and L3 are CRs of their own.
- **There is no remote.** No phase can be proven in CI; each phase's proof is a **local** run of
  `scripts/run_stack_e2e.py` against locally built images (`docker build` of both Dockerfiles), and its log is read.
  The CI job is written in phase 4 and first proven by the first push — after which `stack` is admitted (ADR 0040).
- **A draft lives on the branch `draft/stack-e2e-l1`** (one commit, never for `main`): `e2e/`,
  `infra/e2e/docker-compose.e2e.yml`, `scripts/run_stack_e2e.py`, and an `e2e` job in `container.yml`. It is the
  shape, not the build: `TODO(implement)` marks every hole and the web/ locators are placeholders. It predates ADR
  0091, so its `container.yml` job (a dispatch-time gate) and its smoke/full tiers are overtaken — take the package,
  the overlay and the orchestrator from it (`git checkout draft/stack-e2e-l1 -- e2e infra/e2e scripts/run_stack_e2e.py`),
  not the workflow.
- [`scripts/run_web_e2e.py`](../scripts/run_web_e2e.py) owns the ADR 0053 sweeps, bound to the dev stack's container
  names and root credential; phase 3 moves them, and `run_web_e2e.py` must stay byte-for-byte in behaviour (its own
  CI job, its `--upgrade` proof, `validate_e2e_discovery.py` / `validate_e2e_canaries.py` import from it).
- Guards that anchor on what this CR touches — `changes/README.md` § *before reshaping a symbol, grep `scripts/`*:
  `validate_release_identity.py` (registry writes only on a dispatch: an unpinned `push:` on a build step is read as a
  push — measured on the draft, fixed with `push: false`), `validate_image_pins.py` (Pebble's digest; it scans only
  **tracked** files — Pebble is pinned since phase 1 — and it reads every module-level `<NAME>IMAGE = "…"` Python
  constant as an image, so a path constant in an orchestrator is not named `…IMAGE`), `validate_npm_installs_gated.py` (the
  new workspace member installs through `npm_ci_no_deprecated.py`), `validate_action_pins.py`,
  `validate_compose_posture.py` (production file only, **decided in phase 1**: the overlay never runs on an operator's
  host, so it keeps the posture by hand and says so in its header; the property worth a guard there is a different
  one — that the overlay changes no operator service beyond the ACME trust — and is not built), `validate_coverage.py`
  (`SURFACES`, `FILE_ROOTS`, `ADMITTED`).
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
| Every gate `publish` waits on | `grep -n "needs\.\|needs:" .github/workflows/ci.yml` |
| Coverage files that would name `stack` | `grep -rln "off-state-in-a-browser\|setup-server\|configure-object-store" docs/coverage` |

## Phases

Done when, per phase: the phase's local run is green and its log read; the guards in § Context green; the
trust-chain green. Each phase names its bites — the case that must make the new check fail.

- [x] 1 **A bare boot.** `run_stack_e2e.py` extracts the recipe from a locally built image, writes the `.env` from
      the image's template, lays the Pebble overlay over it (Pebble pinned by digest), brings it up with
      `--wait`, asserts the certificate served on `https://veyq.test` chains to Pebble's root (Python `ssl`, that root
      the only CA), writes `compose.log`, tears down with `down -v`. No browser yet. Bites: `/acme` mounted read-only (Traefik's
      silent failure) → red at the certificate assertion; a variable added to `.env.example` and not filled → red at
      `up` naming it; the run's project never touches a container of another project (`docker ps` before and after).
      **Done 2026-10-16**, against `veyq:e2e-local` and `veyq-admin:e2e-local` built from this tree (app 5:28 min,
      admin 0:30 min, a warm cache). Green, log read (all seven services; Pebble issued for `veyq.test`; Traefik logged
      no error; the app started in 3.7 s and provisioned the store): **21–28 s end to end** over three green runs —
      preflight 0.2–0.8, extract 0.4–0.8, env 0.0, **up to `--wait` 18–22**, certificate 0.2–1.4, evidence 0.2–1.2,
      teardown 1.7–2.4, host 0.2 s. Every stage is timed in the summary. Bites, each red once:
      (1) an app image whose recipe mounts `veyq-acme:/acme:ro` (built `FROM` the candidate with only the recipe
      replaced, so the defect ships in the image as it would) → `up` green, **red at certificate** after 120 s, `:443
      serves CN=TRAEFIK DEFAULT CERT`, Traefik's log `open /acme/acme.json: read-only file system`; (2) the same with
      `VEYQ_BITE_SECRET=` in `.env.example` and `${VEYQ_BITE_SECRET:?…}` in the recipe → **red at up**, `required
      variable VEYQ_BITE_SECRET has no value`; (3) a decoy project `veyq-e2e-decoy` running, the run's project name
      forced onto it (snapshot-and-restore of the script) → **red at host**, `container veyq-e2e-decoy-mariadb-1
      (project veyq-e2e-decoy) is gone`. The host check is permanent, not a one-shot: every run compares every
      container's state and start time and every volume before and after, and asserts nothing of its project is left.
      What it changed against the plan:
      - **The NSS db moves to phase 2**, where the browser that reads it is; phase 1 asserts with Python `ssl` alone.
        Phase 2 has a question to answer there: `certutil` (libnss3-tools) is not on macOS, and Chromium on macOS does
        not read an NSS db, so a local run on a Mac needs another way to trust the root than CI's Linux one.
      - **Teardown and evidence address the project by name** (`docker compose -p <project> down -v`, from its
        labels), never through the files: bite 2's first run showed `down` refuses the same `${VAR:?…}` `up` did, so
        a recipe `up` half-applied would have been left running. The host stage proves the name-only `down` leaves
        nothing.
      - **The extracted recipe is not self-sufficient**: the image carries `docker-compose.prod.yml` and
        `.env.example` but the recipe mounts `./garage.toml`, which still comes from the checkout — the limit
        `run/new-instance.mdx` states for every operator. The run copies it in and says so on every run; closing it
        (the file into the image) is not this CR's.
      - Pebble is **2.10.1** (the draft named 2.8.0; 2.10.1 is current), run non-root (`65532`), read-only, no
        capabilities — measured — with its management port on an ephemeral loopback port.
      - `PEBBLE_VA_ALWAYS_VALID` means Traefik's HTTP-01 router on :80 is not exercised; the overlay and the
        orchestrator's limits say so.
- [x] 2 **First run in a browser.** `e2e/` joins the npm workspace (its own `npm run check`), Playwright pinned;
      `first-run.setup.ts` through to a created tenant and its invite link; `confineToInstance` on every context;
      the Secret Key read back from the kit. Bites: a renamed admin-web label → red naming the locator; a page
      request to another origin (injected `fetch('https://example.org')`) → red listing it; the HIBP stub removed →
      red, the run has no route out. Browser pass: this phase IS one — the trace of a green run is attached.
      **Done 2026-10-16**, against `veyq:e2e-p2` and `veyq-admin:e2e-p2` built from this tree after the lockfile
      gained `e2e/` (admin 30 s, app 4:34 min, warm cache; both Dockerfiles' `npm ci` skip the workspace they cannot
      see, as admin-web's already skipped `web/`). Green, log read (Pebble issued for `veyq.test` and, at run time,
      `family.veyq.test`; the app picked up its truststore; nothing red in any container's log): **31.9–33.9 s end to
      end** over three green runs under a load average of 22–39 (another session's guards) — pebble 1.0–1.5, **up to
      `--wait` 16.2–16.7**, certificate 0.0–2.1, **browser 12.4–13.0** (five tests in 11.3 s: the domain says it is not
      set up 0.3, owner + kit + sign-in 4.6–5.2, store check 1.2, tenant + invite link 0.9, the invite link over the
      tenant's own certificate 2.5–2.7), teardown 1.3–1.6. Far inside ADR 0091's 15-minute lane. What was built:
      - `e2e/` from the draft, reshaped: no tiers; one `setup` project, five tests in a serial file, each tagged with
        its criterion (`@uc.admin.setup-server:status-required-when-fresh`, `…:create-owner-and-complete`,
        `@uc.admin.configure-object-store:server-owner-sets-and-checks-the-store`,
        `@uc.admin.create-tenant:create-pending-tenant` + `…:invite-link-handoff`,
        `@uc.admin.tenant-reachability:domain-actually-routes`); the draft's scenario placeholders and `canaries.ts`
        left for phases 3 and 5. `@playwright/test` **1.64.1** exactly (current on 2026-10-16), `npm run check` =
        lint + typecheck + `playwright test --list`. The kit's Secret Key is read with `@veyq/crypto/emergencyKit`'s
        `parseKit` from the PDF bytes. `instance.json` hands owner, Secret Key, tenant domain and invite link on.
      - **The browser runs in the Playwright image** (`mcr.microsoft.com/playwright:v1.64.1-noble`, index digest),
        the overlay's `browser` service behind a profile, `compose run --rm` on the run's `edge` + `admin` networks,
        as the host's uid, read-only root, no capabilities. The domain resolves to `traefik` on `edge`
        (`--host-resolver-rules`, every other name `~NOTFOUND`); the admin console is reached through an in-container
        loopback tunnel to `admin:8080` at `http://localhost:18081` — a secure context, which admin-web needs.
        Rejected and why, in ADR 0090 decision 3. Preflight refuses a library/image version mismatch.
      - **The trust store without `certutil`** (not in the image): `infra/e2e/nss_trust_root.py` writes Chromium's
        NSS db through `ctypes` against the image's `libnss3`. A Chrome `CACertificates` policy was tried first —
        Playwright's Chromium does not read it.
      What it changed against the plan:
      - **The instance needs what the internet gives it** — found by the first run: the store check uploads through
        the public origin `https://veyq.test` from inside the app container (`ConnectException`), so no tenant could
        be created. The overlay now gives Traefik the alias `veyq.test` on `edge` and the app a truststore holding
        Pebble's root and nothing else (written by the candidate image's own `keytool`, read via
        `JAVA_TOOL_OPTIONS`); Pebble therefore starts as a stage of its own before `up`. ADR 0090 decision 1 says so.
        The overlay's posture claim moved with it: it adds two services and the DNS name and CA trust, nothing else.
      - **CSP violations count as foreign requests.** Bite 2 as written stayed **green** on the first try: admin-web's
        CSP refuses `fetch('https://example.org/')` in the renderer, so no route ever sees it. `confineToInstance`
        now records every `securitypolicyviolation` a page reports beside the aborted requests.
      - **The browser opens the domain itself** (the not-set-up page, then the invite link on the tenant's domain), so
        a certificate not from Pebble is a red navigation and not only phase 1's red certificate stage.
      Bites, each red once and restored by snapshot-and-restore: (1) admin-web's `store.check` renamed to "Run the
      store check" in an admin image built from that tree → **red in 15.7 s**, `waiting for getByRole('button',
      { name: 'Check store' })` (`actionTimeout` 15 s, so a renamed label is red in seconds); (2) an injected
      `fetch('https://example.org/')` on the setup page → **red**, listing `refused by http://localhost:18081's CSP:
      connect-src https://example.org/`; (3) the HIBP branch of the route removed → **red**, "the breach check asked
      once" received 0, and the listing `GET https://api.pwnedpasswords.com/range/5ED45`; (3b) every route set aside
      → the range query fails with `net::ERR_NAME_NOT_RESOLVED` (the trace's network log): no route out even past the
      routes; (4) an app image whose recipe mounts `veyq-acme:/acme:ro`, the certificate stage set aside → **red at the
      first navigation**, `page.goto: net::ERR_CERT_AUTHORITY_INVALID at https://veyq.test/`, Traefik's log naming the
      ACME failure. The trace of a green run: `.e2e-stack-out/<stamp>/playwright/*/trace.zip`, one per test
      (`npx playwright show-trace`), and the HTML report under `report/`.
- [ ] 3 **The Memory round trip, swept.** The sweeps move into `scripts/e2e_sweeps.py`, parameterised by compose
      project, imported by both orchestrators; `run_web_e2e.py`'s own run is unchanged (its CI job's steps, run locally,
      green). `memory-roundtrip.spec.ts` with real web/ locators; every typed value through `recordTyped`; the run
      refuses a green with zero executed tests (junit.xml). Every scenario carries its criterion tag
      (`@uc.<feature>.<use-case>:<criterion-id>`, ADR 0091 decision 3). Bites: a canary written into a container log by
      hand → red naming canary and log; a selection that executes no test → red, not green.
- [ ] 4 **L1 in the pipeline.** The lane job per architecture after the build (a pull request: amd64, built and tested
      in the same job, nothing pushed; a push: both architectures, `build-<sha>-<arch>` pulled by digest), and the
      snapshot publish needs it with `success`; the gate promoted to a guard — `validate_release_identity.py` asserts the
      snapshot waits on L1 (bite: drop the `needs` → red). The criterion-tag guard: every tag names a criterion, every
      `stack` ref names a tagged test (bites: a tag with a typo → red; a ref to an untagged test → red). Coverage: `stack`
      added to `SURFACES` and `FILE_ROOTS` (→ `e2e/`), not to `ADMITTED`; the served use-cases gain a `stack` gap
      "awaiting the first CI run (no remote)". Proof: the job's steps run locally in order; the CI run waits for the remote.
      **Runtime is budgeted, not hoped for.** The orchestrator prints each stage's wall time (pull or build, browser
      install, boot to `--wait`, each Playwright project, sweeps, teardown) into its summary; the job's
      `timeout-minutes` is ADR 0091's 15; the browser install is cached by Playwright's version. Estimated at refine,
      not measured: ~6–10 min per architecture for the first scenarios; the first CI run's numbers replace it. A lane
      that grows past its budget is sharded with a stack per shard, never made faster by weakening what ships (no
      lowered Argon2 parameters, no skipped TLS).
- [ ] 5 **Capability off states — inbox #447's hand pass, automated.** `capability-off-states.spec.ts` for the four ids,
      through admin-web's Server → Capabilities and back, asserting what #447's row recorded: the affordances gone
      ("New drop box" and its tab, "Sign in with a QR code", "Add a device", "Stay signed in"), a trusted and a
      remembered device asking for password + Secret Key with the `unlock.deviceHeldBack` hint once the email is
      entered (the lookup is keyed on it, inbox #450), no DELETE/POST on `/api/account/wraps`, everything restored when
      switched on. Its coverage ref lands in `uc.admin.switch-capabilities.yaml` under `stack.e2e`; the
      `off-state-in-a-browser` gap's "does not run in CI (inbox #448)" then names this spec, until `stack` is admitted.

## Definition of done

A local run of `scripts/run_stack_e2e.py` against both locally built images is green, with every bite above made red
once; the pipeline gates the snapshot on L1; the trust-chain is green; ADR 0090's Status says what is built and that
the CI run awaits the remote; [How veyq is tested](../site/src/content/docs/security/how-veyq-is-tested.md) moves
L1 from designed to built, with what has run where (ADR 0091 decision 8); the first-run Run page (`site/src/content/docs/run/first-run.mdx`)
drops its "not exercised on a running instance" note in favour of naming this suite. Follow-up, not this CR: the
chart on `kind` (ADR 0090 answer 3).

## Retrospective

(filled at close)
