# ADR 0090 — End-to-end tests boot the release candidate and drive it in a real browser

- Status: Accepted 2026-10-16; built so far: L1 installs the candidate through `veyqctl install --unsigned-local` from its release assets (decision 1), then first run, the Memory round trip and the first feature scenario (the capability off states, decision 7) in a browser, and the ADR 0053 sweeps over the run's stack (`scripts/run_stack_e2e.py`, `scripts/e2e_sweeps.py` and `e2e/`), and lane L1's job in `ci.yml` (`stack`, per architecture after the build; the publish waits on it, decision 2 — it pulls `build-<sha>-<arch>` by digest where a run pushed it, today the dispatch, and builds in its own job where none did, until a push publishes the snapshot (ADR 0091 decision 5)) — run locally, step by step; no CI run, which awaits the remote
- Date: 2026-10-16

## Context

What a green run proves today stops short of what a Server Owner runs:

- **`web/`'s `VEYQ_E2E` suites** (`scripts/run_web_e2e.py`) are real and strong — real crypto, a real MariaDB and
  Garage, the ADR 0053 sweeps — but they run in Node against the **fast-jar** on the **dev** compose stack. No
  browser, no image, no production recipe, no Traefik in front, no admin container.
- **admin-web has no suite above unit level**, and its `npm run check` ran only in `container.yml`'s `test` (in `ci.yml`'s `frontend` since ADR 0091).
- **The production recipe has never been brought up by anything** (`site/src/content/docs/run/new-instance.mdx`:
  "No published release has been installed on either target yet"; `first-run.mdx`: "not exercised on a running
  instance"). `validate_compose_posture.py` reads the file and runs nothing; ADR 0071 says a change to it is proven
  by a live `up` — by hand.
- **A whole class of defects is visible only from a browser** (docs/ai/README.md § "a UI slice is not done before it
  was clicked": the `href="#"` that cleared a share key, the card actions hidden up to 1120 px) — and only from the
  image: Dockerfile layers (`capabilities.json` at `Dockerfile:73`), nginx's proxy to the admin listener, Traefik's
  dynamic tenant routes, the HSTS/cookie behaviour behind TLS, ACME issuance into a read-only, non-root `/acme`.
- **The publish workflow already has the seam** (`container.yml` then, `ci.yml` since ADR 0091). A publish dispatch pushes `build-<sha>-<arch>` per image and
  architecture, then `publish` assembles, tags (snapshot or release) and signs them. A snapshot is a published,
  signed `<major>.<minor>-SNAPSHOT`.

## Decision

1. **The unit under test is the release candidate, installed the way a Server Owner installs it.** The run boots the
   two candidate images from the candidate's **release assets** — the recipe set, `veyqctl` and the manifest naming
   both images by digest, the very files a promotion publishes as the immutable GitHub Release ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §3) — through
   `veyqctl install` with `--source` pointed at a local directory in the mirror layout (§7) and `--unsigned-local`
   for images that are not yet signed. So the operator's path is the tested path, and no harness re-implements it.
   The only file laid over the recipe is `infra/e2e/docker-compose.e2e.yml`, the instance's operator override file
   (`compose.override.yml`, which `veyqctl` lays over the recipe on every command), which adds an ACME test
   server and the browser, and gives the instance only what the public internet gives an operator's: DNS for its own
   domain (an alias of Traefik on `edge`, so the app reaches its public origin) and a CA it trusts (the run's Pebble
   root, in the app's truststore and Traefik's ACME client). Measured in phase 2: the store check uploads through the
   public origin from inside the app container, so without them no tenant can be created.

2. **It gates the snapshot, on the exact bytes it publishes.** On a push to `main` or `release/<major>.x` the lanes
   pull `build-<sha>-<arch>` by digest, and the snapshot is published on those digests only when every lane is green;
   a release promotes that snapshot without building again
   ([ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decision 5), so a snapshot and a
   release are both only ever names for bytes that passed. A pull request builds and tests in the same job and
   publishes nothing. Both architectures run on a push, since nothing else ever starts the arm64 image.

3. **TLS is real.** Traefik obtains its certificate from **Pebble** (Let's Encrypt's test ACME server) through the
   recipe's existing `VEYQ_ACME_CASERVER`; Pebble's per-run root is put into the browser's trust store and
   `ignoreHTTPSErrors` stays off. A Traefik that cannot write `/acme` fails a navigation instead of serving its
   default certificate (ADR 0071's silent failure). Domains are `*.veyq.test`, resolved by Chromium's
   `--host-resolver-rules`, so the same run works on a laptop with no `/etc/hosts` edit.

   **The browser runs in a container, never on the host** (decided at phase 2's go, 2026-10-16): macOS has no
   `certutil`, and Chromium on macOS reads no NSS db, so a host browser would need a second trust path for laptops
   that CI never runs. It runs in the official Playwright image, pinned by digest, as the overlay's `browser` service
   (behind a profile, started by `docker compose run`), on the run's own `edge` and `admin` networks — locally
   exactly as in CI. How it reaches the instance without the run publishing anything the recipe does not:
   - **the domain through Traefik**: on `edge`, `veyq.test` and every `*.veyq.test` resolve to the `traefik`
     service, as public DNS resolves an operator's domain to the host, and every other name resolves to nothing;
   - **the admin console through a tunnel**: on `admin`, a loopback listener inside the container forwards the admin
     port to `admin:8080` — the in-container `ssh -L` of the Run area — so the console opens at
     `http://localhost:<ADMIN_PORT>`, the secure context admin-web needs (`crypto.subtle`, the clipboard, Secure
     cookies over HTTP), which `http://admin:8080` is not.

   Rejected: the admin port the recipe publishes on the host's loopback — a container reaches the host's 127.0.0.1
   on Docker Desktop and not on a Linux runner, so a laptop would run another path than CI. **The trust store
   without `certutil`**: the image carries `libnss3` (Chromium links it) and Python, not `libnss3-tools`, and
   installing it would need the network at run time; `infra/e2e/nss_trust_root.py` calls the NSS functions
   `certutil -A` calls, through `ctypes` — measured: Chromium loads a page whose leaf the root issued, and refuses it
   (`ERR_CERT_AUTHORITY_INVALID`) without. A Chrome `CACertificates` policy file was tried first and is not read by
   Playwright's Chromium.

4. **The driver is Playwright, in a workspace package `e2e/`.** TypeScript like both surfaces, Apache-2.0, actively
   maintained, its own `npm run check`. It sees web/ and admin-web/ only as a browser does and imports neither; it
   may import `@veyq/crypto` (the reason it joins the npm workspace, as ADR 0065 allows for that one package — it
   reads the Secret Key back out of the Emergency Kit with the shared `veyqkit:` codec). `@playwright/test` is pinned
   **exactly** to the Playwright image's version, since the browser is baked into the image and a library one patch
   apart fails at launch; the orchestrator refuses a mismatch. The container installs nothing: it mounts the
   checkout, and the root install that went through the deprecation gate, read-only.

5. **The orchestrator is Python** (`scripts/run_stack_e2e.py`), like `run_web_e2e.py`: boot, trust root, Playwright,
   sweeps, evidence, teardown. The ADR 0053 sweeps move into a module both orchestrators import, parameterised by
   compose project; the browser suite records what it **typed** as its canaries.

6. **The browser may reach the instance and nothing else.** Every request to another origin is aborted and fails the
   test, except the HIBP range query, answered locally with no match. Self-hostable by default, checked per page.
   What a page **tried** counts too: a page's own CSP refuses most foreign requests inside the renderer, where no
   route sees them (measured in phase 2 — an injected `fetch` on admin-web stayed green until CSP violations were
   recorded beside the aborted requests). And the browser's resolver knows the instance's names only, so a request
   no route decided finds no address.

7. **One suite, run whole, as the lanes of ADR 0091 decision 3.** This ADR's harness is lane L1 (the candidate,
   fresh): first run in admin-web, a tenant, a Memory written on one device and read on another, a READ link opened
   anonymously, the feature scenarios (starting with the capability off states inbox #447 ran by hand), the sweeps.
   Every scenario is tagged with the acceptance criterion it covers. The same harness boots L2's predecessor and L3's
   restores. Retries are 0.

8. **It is not a replacement for `run_web_e2e.py`.** That layer stays: per-file fresh instances, API-level, fast to
   bisect, and the home of the upgrade proof. This one is few scenarios, one instance per run, and the shipped artifact.

## Rejected alternatives

- **Testcontainers (JUnit or Node).** It would either re-declare the stack in code — a second copy of the recipe that
  drifts from the one that ships, "one truth and one lie" — or wrap `docker compose`, which adds a dependency and
  removes nothing. JUnit would also put browser tests in a third language beside the two TypeScript surfaces. Where
  Testcontainers would earn its place is the backend's own integration tests (a real MariaDB instead of H2 for a
  `@QuarkusTest`); that is a separate question.
- **Testing the published snapshot.** Testing after the publish tests a name that is already pullable; the gate has to
  sit before it — which is why the lanes test `build-<sha>` and the snapshot follows them, and why a release promotes
  rather than builds (ADR 0057 §1, reopened for exactly this by ADR 0091).
- **Extending `run_web_e2e.py`.** Its premise is the dev stack plus a jar, fresh per file; this one's is the image
  plus the production recipe, one instance per run. Sharing the sweeps is the overlap worth having.
- **Kubernetes now.** The chart is the second target (ADR 0064) and gets the same smoke tier later on a `kind`
  cluster: the suite needs only `VEYQ_STACK_FILE`, so the orchestrator is the only thing that changes.

## Consequences

- **Nothing is proven before the repository has a remote.** Until then the proof is a local run of
  `scripts/run_stack_e2e.py` against locally built images, and — ADR 0040 — no coverage ref may point at `e2e/`
  until a CI run's log shows the test executed.
- **The coverage model gains a surface, `stack`** (answer 2), unadmitted until a CI run executed it.
- **The harness follows the operator's tool.** With [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md), L1 is `veyqctl install` and ADR 0091's L2 and L3 are
  `veyqctl upgrade` and `veyqctl restore`; a defect in the tool an operator runs is then a red lane, not a gap
  between the tool and a test that booted the stack its own way.
- **Port 80/443 belong to the run.** The recipe publishes Traefik on them, so a local run needs them free; the admin
  port is the run's own (`ADMIN_PORT`), never the developer's 8081.
- **A push gets slower** by a stack boot plus the suite, per architecture in parallel; ADR 0091 budgets the lane at 15
  min. Estimated at refine, not measured: ~6–10 min for the first scenarios. What it rests on: the image is
  built once by `build` and only pulled (dispatch) or rebuilt from its cache (dry run); one instance per run, not per
  file; a cold birth is a few Argon2id derivations at `t=3, m=32 MiB` (`packages/crypto/src/v1.ts`); the fast-jar
  started in 2–6 s in a local `run_web_e2e.py` run on 2026-10-15. The first CI run's per-stage times replace this
  paragraph. Speed is never bought by weakening what ships.
- **Published** for security researchers on the docs site, with every other stage: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).
- **The Run pages become checkable.** first-run.setup.ts uses admin-web's own labels in the order first-run.mdx gives
  them; a renamed button or a reordered step is a red run.

## Answered at refine (2026-10-16, accepted with this ADR)

1. **The whole suite on every push and pull request** — replaced at the same refine by ADR 0091 decision 4, which the
   owner chose over the first answer (smoke per push, the full tier per publish): a regression is cheapest found at
   the commit that causes it.
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
