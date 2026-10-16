# ADR 0090 — End-to-end tests boot the release candidate and drive it in a real browser

- Status: Accepted 2026-10-16; nothing of it is built (`changes/cr-stack-e2e.md`)
- Date: 2026-10-16

## Context

What a green run proves today stops short of what a Server Owner runs:

- **`web/`'s `VEYQ_E2E` suites** (`scripts/run_web_e2e.py`) are real and strong — real crypto, a real MariaDB and
  Garage, the ADR 0053 sweeps — but they run in Node against the **fast-jar** on the **dev** compose stack. No
  browser, no image, no production recipe, no Traefik in front, no admin container.
- **admin-web has no suite above unit level**, and its `npm run check` runs only in `container.yml`'s `test`.
- **The production recipe has never been brought up by anything** (`site/src/content/docs/run/new-instance.mdx`:
  "No published release has been installed on either target yet"; `first-run.mdx`: "not exercised on a running
  instance"). `validate_compose_posture.py` reads the file and runs nothing; ADR 0071 says a change to it is proven
  by a live `up` — by hand.
- **A whole class of defects is visible only from a browser** (docs/ai/README.md § "a UI slice is not done before it
  was clicked": the `href="#"` that cleared a share key, the card actions hidden up to 1120 px) — and only from the
  image: Dockerfile layers (`capabilities.json` at `Dockerfile:73`), nginx's proxy to the admin listener, Traefik's
  dynamic tenant routes, the HSTS/cookie behaviour behind TLS, ACME issuance into a read-only, non-root `/acme`.
- **`container.yml` already has the seam.** A publish dispatch pushes `build-<sha>-<arch>` per image and
  architecture, then `publish` assembles, tags (snapshot or release) and signs them. A snapshot is a published,
  signed `<major>.<minor>-SNAPSHOT`.

## Decision

1. **The unit under test is the release candidate, not the repository.** The run boots the two candidate images
   and the compose recipe **extracted from the app image** (`/opt/veyq/deploy/`), with a `.env` generated from the
   image's own `.env.example`. The only file laid over the recipe is `infra/e2e/docker-compose.e2e.yml`, which adds
   an ACME test server and changes nothing an operator's instance has.

2. **It gates the publish, on the exact bytes it publishes.** A job `e2e` in `container.yml` needs `build`; `publish`
   needs `e2e`. On a dispatch it pulls `build-<sha>-<arch>` by digest — the manifests `publish` then tags and signs —
   so a snapshot and a release are both only ever names for an image that passed. On a push or pull request (a dry
   run, which pushes nothing) it rebuilds from `build`'s cache and runs the smoke tier; that informs and gates nothing.
   Both architectures run, since nothing else ever starts the arm64 image.

3. **TLS is real.** Traefik obtains its certificate from **Pebble** (Let's Encrypt's test ACME server) through the
   recipe's existing `VEYQ_ACME_CASERVER`; Pebble's per-run root is put into the browser's trust store and
   `ignoreHTTPSErrors` stays off. A Traefik that cannot write `/acme` fails a navigation instead of serving its
   default certificate (ADR 0071's silent failure). Domains are `*.veyq.test`, resolved to the runner by Chromium's
   `--host-resolver-rules`, so the same run works on a laptop with no `/etc/hosts` edit.

4. **The driver is Playwright, in a workspace package `e2e/`.** TypeScript like both surfaces, Apache-2.0, actively
   maintained, its own `npm run check`. It sees web/ and admin-web/ only as a browser does and imports neither; it
   may import `@veyq/crypto` (the reason it joins the npm workspace, as ADR 0065 allows for that one package).

5. **The orchestrator is Python** (`scripts/run_stack_e2e.py`), like `run_web_e2e.py`: boot, trust root, Playwright,
   sweeps, evidence, teardown. The ADR 0053 sweeps move into a module both orchestrators import, parameterised by
   compose project; the browser suite records what it **typed** as its canaries.

6. **The browser may reach the instance and nothing else.** Every request to another origin is aborted and fails the
   test, except the HIBP range query, answered locally with no match. Self-hostable by default, checked per page.

7. **Two tiers, one suite.** `@smoke` (first run in admin-web, a tenant, a Memory with a photo written on one device
   and read on another, a READ link opened anonymously, the sweeps) runs on every dry run and every publish; the full
   set (starting with the capability off states inbox #447 ran by hand) runs on every publish. Retries are 0.

8. **It is not a replacement for `run_web_e2e.py`.** That layer stays: per-file fresh instances, API-level, fast to
   bisect, and the home of the upgrade proof. This one is few scenarios, one instance per run, and the shipped artifact.

## Rejected alternatives

- **Testcontainers (JUnit or Node).** It would either re-declare the stack in code — a second copy of the recipe that
  drifts from the one that ships, "one truth and one lie" — or wrap `docker compose`, which adds a dependency and
  removes nothing. JUnit would also put browser tests in a third language beside the two TypeScript surfaces. Where
  Testcontainers would earn its place is the backend's own integration tests (a real MariaDB instead of H2 for a
  `@QuarkusTest`); that is a separate question.
- **Testing the published snapshot.** Testing after the publish tests a name that is already pullable; the gate has to
  sit before it. A release that *promotes* a tested snapshot instead of building again would be stronger still, but
  the version is baked in at build (`VEYQ_VERSION`), so a promoted snapshot would report `-SNAPSHOT` as a release —
  it would mean reopening ADR 0057 §1. Gating on `build-<sha>` gives the same guarantee without it.
- **Extending `run_web_e2e.py`.** Its premise is the dev stack plus a jar, fresh per file; this one's is the image
  plus the production recipe, one instance per run. Sharing the sweeps is the overlap worth having.
- **Kubernetes now.** The chart is the second target (ADR 0064) and gets the same smoke tier later on a `kind`
  cluster: the suite needs only `VEYQ_STACK_FILE`, so the orchestrator is the only thing that changes.

## Consequences

- **Nothing is proven before the repository has a remote.** Until then the proof is a local run of
  `scripts/run_stack_e2e.py` against locally built images, and — ADR 0040 — no coverage ref may point at `e2e/`
  until a CI run's log shows the test executed.
- **The coverage model gains a surface, `stack`** (answer 2), unadmitted until a CI run executed it.
- **Port 80/443 belong to the run.** The recipe publishes Traefik on them, so a local run needs them free; the admin
  port is the run's own (`ADMIN_PORT`), never the developer's 8081.
- **A publish gets slower** by a stack boot plus the full tier, per architecture in parallel; a dry run by the smoke
  tier, which gates nothing. Estimated at refine, not measured: ~6–10 min for smoke. What it rests on: the image is
  built once by `build` and only pulled (dispatch) or rebuilt from its cache (dry run); one instance per run, not per
  file; a cold birth is a few Argon2id derivations at `t=3, m=32 MiB` (`packages/crypto/src/v1.ts`); the fast-jar
  started in 2–6 s in a local `run_web_e2e.py` run on 2026-10-15. The first CI run's per-stage times replace this
  paragraph. Speed is never bought by weakening what ships.
- **The Run pages become checkable.** first-run.setup.ts uses admin-web's own labels in the order first-run.mdx gives
  them; a renamed button or a reordered step is a red run.

## Answered at refine (2026-10-16, accepted with this ADR)

1. **Smoke on every push and pull request, the full tier on every publish** — Decision 7. A dry run is where a
   regression is cheapest to find; the publish is where it must not pass.
2. **Coverage gets a surface of its own, `stack`**, resolving refs relative to `e2e/` (`validate_coverage.py`'s
   `SURFACES` + `FILE_ROOTS`, the extension point that file names). Filing a spec under `web` or `admin-web` would
   claim a surface's own CI job ran it, which none does; one spec also drives both. `stack` is not OWED, and it is
   admitted (ADMITTED) only after a CI log shows its suite executed (ADR 0040) — until the repository has a remote,
   the use-cases it serves carry `stack` under `gaps:` with that reason.
3. **Kubernetes is a follow-up CR**, after the compose target is green in CI: the chart has its own unproven publish
   (ADR 0064), and two unproven targets in one CR would leave a red with two suspects.
4. **First run is driven through the UI, once per run, by the `setup` project** — it is the scenario, not a fixture.
   A scenario that needs more state than first run leaves (a second member, a populated space) builds it through the
   UI too while the suite is small; an API seeder is introduced only when a scenario's setup dominates its runtime, and
   then it is `@veyq/crypto` plus the generated clients inside `e2e/`, never an import from `web/src/test/`.
