# ADR 0090 — End-to-end tests boot the release candidate and drive it in a real browser

- Status: Accepted
- Built: partly (not built: decision 2's push gate on the published snapshot's digests, decision 7's boot of L2's predecessor and L3's restores)
- Date: 2026-10-16
- Model: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)

## Context

What a green run proves today stops short of what a Server Owner runs:

- **`src/web/`'s `VEYQ_E2E` suites** (`scripts/run_web_e2e.py`) are real and strong — real crypto, a real MariaDB and
  Garage, the ADR 0053 sweeps — but they run in Node against the **fast-jar** on the **dev** compose stack. No
  browser, no image, no production recipe, no Traefik in front, no admin container.
- **admin-web had no suite above unit level.**
- **The production recipe had never been brought up by anything.** `validate_compose_posture.py` reads the file and
  runs nothing; ADR 0071 said a change to it is proven by a live `up` — by hand.
- **A whole class of defects is visible only from a browser** (the `href="#"` that cleared a share key, card actions
  hidden below a width) — and only from the image: Dockerfile layers, nginx's proxy to the admin listener, Traefik's
  dynamic tenant routes, the HSTS/cookie behaviour behind TLS, ACME issuance into a read-only, non-root `/acme`.
- **The publish already has the seam.** A publish dispatch pushes `build-<sha>-<arch>` per image and architecture,
  then `publish` assembles, tags (snapshot or release) and signs them.

## Decision

1. **The unit under test is the release candidate, installed the way a Server Owner installs it.** The run boots the
   two candidate images from the candidate's **release assets** — the recipe set, `veyqctl` and the manifest naming
   both images by digest, the very files a promotion publishes as the immutable GitHub Release ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §3) — through
   `veyqctl install` from a local source in the mirror layout ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)
   decision 7), accepting images that are not yet signed. So the operator's path is the tested path, and no harness
   re-implements it. The only file laid over the recipe is the instance's operator override file, the one `veyqctl`
   lays over the recipe on every command: it adds an ACME test server and the browser, and gives the instance only
   what the public internet gives an operator's — DNS for its own domain (so the app reaches its public origin), a CA
   it trusts (the run's ACME test root) and a mail relay whose mailbox the suite reads. The store check uploads through
   the public origin from inside the app container, so without them no tenant can be created. One more **test double,
   like the ACME server**: the release check ([ADR 0097](0097-an-instance-looks-for-new-releases-and-mails-its-server-owner.md))
   reads releases only on a release-stamped build, and the candidate of a push is a `-SNAPSHOT` by design, so the run
   stamps its own instance with the release `veyqctl` installed (on the app's container, never on the image), has it
   check every few seconds instead of daily, keeps it off veyq's real release source (an offline run's check fails,
   ADR 0097), and serves a stub mirror whose releases — newer than the stamp, one of them an unscheduled major — the
   release producer itself writes. No production change: the app's mirror reader verifies no signature, so the
   producer's unsigned files are what it reads. How the overlay and the orchestrator do each of these is theirs to say
   (`scripts/run_stack_e2e.py`'s docstring, the overlay's comments).

2. **It gates the snapshot, on the exact bytes it publishes.** On a push to `main` or `release/<major>.x` the lanes
   pull `build-<sha>-<arch>` by digest, and the snapshot is published on those digests only when every lane is green;
   a release promotes that snapshot without building again
   ([ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decision 5), so a snapshot and a
   release are both only ever names for bytes that passed. A pull request builds and tests in the same job and
   publishes nothing. Both architectures run on a push, since nothing else ever starts the arm64 image.

3. **TLS is real.** Traefik obtains its certificate from **Pebble** (Let's Encrypt's test ACME server) through the
   recipe's own ACME setting; Pebble's per-run root is put into the browser's trust store and certificate errors are
   never ignored. A Traefik that cannot write its ACME store fails a navigation instead of serving its default
   certificate (ADR 0071's silent failure). Domains are `*.veyq.test`, resolved by the browser itself, so the same
   run works on a laptop with no `/etc/hosts` edit.

   **The browser runs in a container, never on the host**: macOS has no
   `certutil`, and Chromium on macOS reads no NSS db, so a host browser would need a second trust path for laptops
   that CI never runs. It runs in the official Playwright image, pinned by digest, as a service of the overlay, on the
   run's own edge and admin networks — locally exactly as in CI. How it reaches the instance without the run
   publishing anything the recipe does not:
   - **the domain through Traefik**: on the edge network the instance's domain and every subdomain resolve to
     Traefik, as public DNS resolves an operator's domain to the host, and every other name resolves to nothing;
   - **the admin console through a tunnel**: on the admin network, a loopback listener inside the container forwards
     to the admin listener — the in-container `ssh -L` of the Run area — so the console opens on `localhost`, the
     secure context admin-web needs (`crypto.subtle`, the clipboard, Secure cookies over HTTP), which the admin
     service's own name is not.

   Rejected: the admin port the recipe publishes on the host's loopback — a container reaches the host's 127.0.0.1
   on Docker Desktop and not on a Linux runner, so a laptop would run another path than CI. **The trust store
   without `certutil`**: the image carries NSS (Chromium links it) and Python, not NSS's tools, and installing them
   would need the network at run time; so the root is added by calling the NSS functions `certutil -A` calls, through
   `ctypes`. A Chrome `CACertificates` policy file is not read by Playwright's Chromium.

4. **The driver is Playwright, in a workspace package `tests/e2e/`.** TypeScript like both surfaces, Apache-2.0, actively
   maintained, its own `npm run check`. It sees src/web/ and src/admin-web/ only as a browser does and imports neither; it
   may import `@veyq/crypto` (the reason it joins the npm workspace, as ADR 0065 allows for that one package — it
   reads the Secret Key back out of the Emergency Kit with the shared `veyqkit:` codec). The library is pinned
   **exactly** to the image's browser, since the browser is baked into the image and a library one patch apart fails
   at launch. The container installs nothing: it mounts the checkout, and the root install that went through the
   deprecation gate, read-only.

5. **The orchestrator is Python** (`scripts/run_stack_e2e.py`), like `run_web_e2e.py`: boot, trust root, Playwright,
   sweeps, evidence, teardown. The ADR 0053 sweeps move into a module both orchestrators import, parameterised by
   compose project; the browser suite records what it **typed** as its canaries.

6. **The browser may reach the instance and nothing else.** Every request to another origin is aborted and fails the
   test, except the two requests a page makes without opt-in, each answered locally: the HIBP range query with no
   match, and the day's ECB rate table of Expenses (the seeded address with a date) with a fixed table (the rates
   joined later, [ADR 0108](0108-expenses-a-pot-is-a-node-its-entries-are-sealed-revisions.md) decision 8).
   Self-hostable by default, checked per page.
   What a page **tried** counts too: a page's own CSP refuses most foreign requests inside the renderer, where no
   route sees them, so CSP violations are recorded beside the aborted requests. And the browser's resolver knows the instance's names only, so a request
   no route decided finds no address.

7. **One suite, run whole, as the lanes of ADR 0091 decision 3.** This ADR's harness is lane L1 (the candidate,
   fresh): first run in admin-web, a tenant, a Memory written on one device and read on another, a READ link opened
   anonymously, the feature scenarios, the sweeps.
   Every scenario is tagged with the acceptance criterion it covers. The same harness boots L2's predecessor and L3's
   restores. Retries are 0.

   **First run is driven through the UI, once per instance, by the setup project** — it is the scenario, not a
   fixture. A scenario that needs more state than first run leaves (a second member, a populated space) builds it
   through the UI too while the suite is small; an API seeder is introduced only when a scenario's setup dominates its
   runtime, and then it is `@veyq/crypto` plus the generated clients inside `tests/e2e/`, never an import from
   `src/web/`'s tests.

   **Run whole, one leg per domain** (ADR 0091 decision 1: a stage that outgrows its budget is sharded): CI runs the
   suite as one leg per domain and architecture, each on an instance of its own with its own sweeps. **A domain is a
   folder** of the suite's feature specs and a Playwright project of the same name. Every leg runs the dependency
   projects whole — the setup, the scenarios, and the instance-wide state other scenarios read — and its domain, whose
   specs read nothing another feature spec left (a spec that writes what no later spec may meet does so in a tenant of
   its own); a test of the suite holds the legs equal to the folders. **A new spec goes into its domain's folder**, so
   nothing is rebalanced: the legs are domains, not bins balanced by time, and a new domain is a new folder and its
   leg, in one change. A domain whose leg grows past about 7 min is split by sub-domain, never at random. *Rejected:*
   Playwright's `--shard`, which splits by test count, not by time. The cost taken: the boot and the dependency
   projects are paid once per leg. One worker per instance until every spec is proven isolated.

   **Traces**: a local run keeps one per test, so a green run on a laptop can be compared with a red one; CI keeps one
   for a failed test only, since a trace per test costs real time on every run. A green CI leg's run directory still
   keeps its logs, report and dumps.

8. **It is not a replacement for `run_web_e2e.py`.** That layer stays: per-file fresh instances, API-level, fast to
   bisect, and the home of the upgrade proof. This one is few scenarios, one instance per run, and the shipped artifact.

9. **One rule for languages and test levels.** L1 proves
   what only the release candidate in a browser can show, once:
   - **Every flow runs in English.**
   - **A second language is the catalogs' and the component tests'**: src/web/'s `i18n.test.ts` holds that both
     languages carry the same keys and every key is used, and selected statements are asserted in both; component
     tests render a panel in either. L1 does not walk a flow a second time to read the same keys in German.
   - **One short German pass per application** proves only what a browser alone shows in German: number and date
     formats, an amount typed with a decimal comma, a date typed in the German field order, long words in the real
     layout, and the locale switch itself.
   - **The exception is the legal texts** — the privacy notice and the terms: their exact wording in each language is
     the claim, so they keep both languages.
   - **A rule already proven below L1** — a slice test with an injected clock, a component test — **is not proven again
     by waiting real time**: L1 shows once that the release candidate does it, and the use case's `stack` gap names the
     test that holds the rest.
   - **The e2e overlay may shorten a server-side interval** (a window, a tick, a poll) so a wait the lane keeps is
     short, and says why its number is safe; it never changes a product default. A client constant with no seam stays
     what it is — but where the product itself acts at once, the lane triggers that instead of waiting: every live poll
     ticks on a tab's return to visible (held by its component tests), so a spec asks for an idle `304` by hiding and
     showing the tab, and keeps one real tick of a poll only for a claim that a visible view updates by itself.
   - **A Service Worker is stopped through DevTools, never waited for.** Playwright attaches DevTools to every worker,
     and with DevTools attached Chromium does not stop an idle one, so a wait for Chromium's idle stop is a wait for nothing;
     DevTools stops it at the moment the test needs. A real pause stays only where the claim is that NOTHING stops
     something that stands still — which a stop cannot show — and the spec says why.
   - **Not taken: seeding a spec's state through the API, or reusing a signed-in session across specs.** Argon2id
     dominates a tenant birth whether the UI or the client code drives it, so seeding saves little; `storageState`
     cannot carry an unlocked veyq session, correctly — the session vault is sealed under a non-extractable key, and
     copying it would itself be a defect; and both loosen decision 4's boundary (the seeding imports the client's code,
     the reuse hands a spec a session another spec opened) for a gain of about a minute a run.

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
  cluster, after the compose target is green in CI: the chart has its own unproven publish, and two unproven targets
  in one change would leave a red with two suspects. The suite needs only the stack file it is pointed at, so the
  orchestrator is the only thing that changes.

## Consequences

- **Nothing is proven before the repository has a remote.** Until then the proof is a local run of
  `scripts/run_stack_e2e.py` against locally built images; what a ref on this lane may claim before a CI run's log
  shows it executed is [ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md) decision 3's.
- **The coverage model gains a surface of its own, `stack`.** Filing a spec under `web` or `admin-web` would claim
  that surface's own CI job ran it, which none does, and one spec drives both clients. `stack` is not owed, and it
  is unadmitted until a CI run executed it, so until then the use-cases it serves carry it under `gaps` with that
  reason.
- **The harness follows the operator's tool.** With [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md), L1 is `veyqctl install` and ADR 0091's L2 and L3 are
  `veyqctl upgrade` and `veyqctl restore`; a defect in the tool an operator runs is then a red lane, not a gap
  between the tool and a test that booted the stack its own way.
- **Port 80/443 belong to the run.** The recipe publishes Traefik on them, so a local run needs them free; the admin
  port is the run's own (`ADMIN_PORT`), never the developer's 8081.
- **A push gets slower** by a stack boot plus the suite, per architecture in parallel; each leg's budget is the
  stage map's ([How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md) § Where and when each
  stage runs, [ADR 0091](0091-one-pipeline-per-commit-a-release-promotes-a-tested-snapshot.md) decision 1). Speed is
  never bought by weakening what ships.
- **Published** for security researchers on the docs site, with every other stage: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md).
- **The Run pages become checkable.** first-run.setup.ts uses admin-web's own labels in the order first-run.mdx gives
  them; a renamed button or a reordered step is a red run.
