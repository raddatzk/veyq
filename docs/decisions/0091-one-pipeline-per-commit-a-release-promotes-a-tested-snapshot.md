# ADR 0091 — One pipeline per commit, every stage on a budget; a release promotes a tested snapshot

- Status: Accepted
- Built: partly (not built: decision 3's lanes L2 and L3, decision 5's promotion and attestations, decision 6, decision 7)
- Date: 2026-10-16
- Model: [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)

## Context

There is no decision about testing as a whole — only single ones: what a coverage claim needs
([ADR 0040](0040-coverage-gaps-and-http-not-a-surface.md)), assertion strength
([ADR 0045](0045-mutation-testing-trust-chain-unit-layer.md)), the wire assertion
([ADR 0053](0053-wire-assertion-canary-sweep.md)), the upgrade proof
([ADR 0057](0057-release-identity-and-deployment-recipe.md) §7), races on MariaDB
([ADR 0078](0078-production-reads-committed-like-its-tests.md)), the stack-level browser suite
([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md)). Each was right on its own; together they grew
into seven workflows nobody budgeted, and the owner wants three kinds of test on top: **an upgrade of a running
instance with data**, **an old backup restored into a new version**, and **feature tests against a running
instance**.

Every one of those stages sent requests somebody wrote down; none asked the API what it does with the requests
nobody wrote. A guard proved a request body's bounds *annotated*, and a few slice tests proved one per mechanism
*takes effect* — but nothing saw path, query or header **parameters** (no test sent an out-of-range one), nothing read
a **response** against its schema, and nothing looked for a **5xx** on malformed input (decision 9).

Three structural costs sat on top of what a run took:

- **`validate_guard_gaps.py` was 90 % of the trust-chain's time.** It tests the guards, not the product: each forgery a
  fresh copy of the tree and a subprocess.
- **The backend and both frontends were checked twice per push**, by two workflows, and the image build waited for the
  second copy.
- **A release built again.** It published bytes no test saw; ADR 0090 gated them by testing the staging images inside
  the dispatch — the right bytes at the latest possible moment.

## Decision

1. **The stages, and the one thing each proves.** Every stage has a budget (the job's `timeout-minutes`) and an
   owner of its slowness: a stage that outgrows its budget is sharded or moved to a later trigger, never made faster
   by weakening what ships (no lowered Argon2 parameters, no skipped TLS, no H2 where MariaDB is the point).

   | Stage | Proves |
   |---|---|
   | **Guards** | the tree keeps its written rules |
   | **Guard gaps**, cases in parallel | the guards still bite as documented |
   | **Surface checks** (each surface's own check; the chart check) | each surface's unit and integration tests |
   | **Races on MariaDB** | ADR 0078 |
   | **API end-to-end** | real crypto against a live backend, fresh per file; backup and restore groups; the wire sweeps |
   | **API fuzzing** (decision 9) | every operation of the API contracts refuses what its contract does not allow, answers only what it declares, and never answers 5xx |
   | **Image build** | every image of the release (app, admin, tools), both architectures, once per commit |
   | **Stack lanes** (ADR 0090's harness, on the built image), in parallel | see 3 |
   | **Mutation** (ADR 0045), path-gated | assertion strength of the scoped files |

   The jobs that are no stage carry a budget too, so that no job runs on GitHub's 360-minute default; a stage proved
   from two predecessors runs a leg per predecessor, each on the job's budget, rather than one leg on a longer one. A
   value no run has measured is an estimate until the first CI run replaces it. A guard holds that every job of every
   workflow has a budget; the values, and each stage's workflow job, trigger and measured time, are the one stage
   map's ([How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md)).

2. **One pipeline per commit, and nothing waits that need not.** One workflow, `ci.yml`, the name a reader looks for.
   The guards, the surface checks, the races, the API end-to-end run, the API fuzzing **and the image build start
   together**; the build does not wait for the tests, because nothing is published until every stage is green — the
   publish needs every other job of the file. The stack lanes need the build. The surface checks run once. The guard
   gaps are not in it (decision 4), and `trust-chain.yml` stays separate and unfiltered, because a docs-only commit is
   validated there and nowhere else.

3. **Three stack lanes, one scenario suite.** The scenarios are split into a **write half** (put content into an
   instance) and a **read half** (open every item and assert it), with one record between them — the shape the
   upgrade proof already has:
   Every lane drives the operator's own tool, `veyqctl` ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)), against the candidate's release assets served as a
   local source — so what is tested is what a Server Owner runs:
   - **L1 feature** — `veyqctl install` of the candidate, fresh: write, then read; plus the feature scenarios that
     need no predecessor. Sharded (decision 1), one leg per domain of the suite, each a fresh install
     of its own ([ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md) decision 7); which languages and which
     waits it keeps is [ADR 0090](0090-end-to-end-tests-boot-the-release-candidate.md) decision 9.
   - **L2 upgrade in place** — `veyqctl install` of the previous release, write; `veyqctl upgrade` to the candidate
     on the same instance (its backup, template check and health wait, ADR 0096 §5); read.
   - **L3 backup import** — every archived backup of the current major and the major entry's (decision 6), each
     restored with its archived pool by `veyqctl restore` into a fresh `veyqctl install` of the candidate (ADR 0056
     §7); read. One runner per backup.

   Feature scenarios are Playwright tests tagged with the acceptance criterion they cover; a guard holds both directions
   — every tag names a criterion, every `stack` ref of a criterion names a tagged test. There is no second wording of a
   criterion.

4. **When what runs.**
   - **Pull request**: everything in 1 except mutation's scope rule, L1 on amd64, nothing published. A pull request
     never writes to a registry.
   - **Push to `main` or `release/<major>.x`**: everything, all three lanes on **both** architectures (what is
     published for an architecture must have passed on it), then the snapshot (decision 5).
   - **Guard gaps** run when what the guards are made of changes, and nightly on `main` — they test the guards, and a
     product commit changes no guard.
   - **Release**: decision 5's promotion; the tests it needs already ran.

5. **Build once, promote.** A green push publishes its images as a **snapshot** — `<major>.<minor>-SNAPSHOT` and
   `sha-<commit>` on the exact digests the lanes tested — signed, with each lane's result attached as a signed
   attestation (lane, architecture, outcome, and its **inputs**: the predecessor digest for L2, the list of backups
   for L3, and the hash of the release-asset set it installed). A snapshot is written once per commit and never
   replaced. **A release is a promotion**: a dispatch names a commit (default: the tip), its snapshot's attestations
   are verified, and the same digests get `<major>.<minor>`, `<major>`, `latest` (from `main`), a release signature
   and the git ref `v<major>.<minor>`; then the **immutable GitHub Release** is created from the asset set
   ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §3). Nothing is rebuilt.
   - **The asset set is the candidate's, too.** The pipeline produces it from the commit and the tested digests
     before the lanes run, and the lanes install exactly it. A snapshot
     gets no GitHub Release (ADR 0096 §3); the promotion produces the set again from the same commit and digests, and
     refuses when its hash differs from the one the attestations name — so the published files are the ones tested.
   - **In CI the candidate is pulled by digest and installed unsigned under a local name**, the one way `veyqctl`
     accepts an unsigned release ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md)); the
     signature check itself is exercised where it can be: by L2's install of the previous, signed release.
   - **The image carries `<major>.<minor>`, never `-SNAPSHOT`.** The minor is a function of the commit (ADR 0057 §1),
     so `0.12` can only ever name this tree, and a snapshot and its release are the same bytes. Whether a digest *is*
     a release is the registry's and the signature's fact, not the image's: `cosign verify` with the release identity
     answers it, and admin-web's release panel says so. The cost: a snapshot that is never promoted shows `0.12`
     too.
   - **A result is reused only for the inputs it ran against.** When a release was cut after the snapshot was tested,
     the predecessor and the archive have moved; the promotion compares the attestations' inputs with the current
     ones and runs **only the missing lanes** on the snapshot's digest before it promotes.
   - **Retention**: the 20 newest unpromoted snapshots per branch and every promoted one; a scheduled job deletes the
     rest.

6. **The backup archive.** Every release publishes the L1 run's capture (ADR 0056's tool, test data only) **with its
   object pool** (`--objects-to`: a capture's store leg is only its index, ADR 0056 §3) as a signed OCI artifact
   beside its images. L3 restores every archived backup of the current major — within a major, everything older must
   open ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)) — and the backup of the candidate's **major entry**
   (ADR 0085 decision 10), the only release [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §5
   enters a new major from. Before the first release the archive is empty, L2 upgrades from the previous snapshot, and
   both inform and gate nothing, as ADR 0057 §7 says for the upgrade proof.

7. **The jar-level upgrade proof retires when L2 and L3 gate.** They prove the same data path on the artifact that
   ships; ADR 0057 §7 then names them. The API end-to-end suites stay: fast, fresh per file, and where a wire defect is
   bisected.

8. **The test concept is published, because it is what stands in for a human review.** veyq's code is written by an
   AI coding agent under the owner's direction and is not reviewed line by line by a person, so what a reader can
   rely on is what the checks show — and only that. The Security area of the docs site carries
   [How veyq is tested](../../site/src/content/docs/security/how-veyq-is-tested.md): each stage of decision 1, what
   it proves and what it cannot, when it runs, what gates a release, and which of it is **built and has run**. It
   claims no more than a run shows: a stage that is designed and not built says so, a number is measured or marked
   an estimate, and until the repository has a remote it says that no stage has run in CI. A CR that adds, removes
   or moves a stage updates that page in the same change.

9. **The API is fuzzed from its contracts, on every commit.** [Schemathesis](https://schemathesis.readthedocs.io/)
   (MIT, maintained — the scanner [ADR 0100](0100-pentests-attack-a-reset-candidate-locally-and-publish-every-runs-record.md)
   decision 2 chose for layer A) generates requests from the API contracts and sends them to a live backend. It is a
   stage of its own, beside the API end-to-end run, not inside it. How it runs is the runner's docstring
   (`scripts/run_api_fuzz.py`).
   - **The target is the fast-jar under the e2e profile, booted by the API end-to-end run's own primitives** — a fresh
     database, the bundled store, rate limits that do not refuse a run's volume. Not lane L1: the candidate's
     production limits answer 429 long before a run is through, its lane owns ports 80 and 443, and its budget is
     spent on the browser. What Traefik and the image add is L1's and the pentests' (ADR 0100) to see.
   - **It runs in an image of our own**, which the runner builds: a Python base pinned by digest like every other
     third-party image (ADR 0057 §6), and every package pinned by version and hash. Schemathesis needs a newer Python
     than the host scripts run on, so the image carries what they cannot. Not the official Schemathesis image: it also
     installs a proprietary, binary-only coverage plugin and loads it on every run; veyq's hooks refuse to load if it
     is ever imported.
   - **The spec files are read as they are**, one invocation per listener: the admin channel's operations against the
     admin listener, the rest against the public one. Nothing is bundled.
   - **It signs in the way the client does.** A seeding script births an instance with the real client crypto and
     hands the run a bearer token and the ids it created; Schemathesis cannot do a sign-in that is client-side
     cryptography, and is not asked to.
   - **What it checks:** every operation with requests the contract does not allow — path, query and header
     parameters included — and with requests it allows. A malformed request is refused with the contract's
     `400 invalid_request` (a path id that is no UUID matches no route and answers `404`), a body past the request
     limit with the declared `413`, and a credential the run cannot forge with the declared `401` or `403`, since
     authentication runs before shape; a request the contract allows is never refused `400`. Every answer is a status
     the operation declares, its body matches the declared schema, a refusal echoes nothing it was sent (developer
     styleguide §7), and no answer is a 5xx.
   - **Positive writes stay below the tenant.** A positive run may change what the run's own identities own below the
     tenant — their account's sealed data, their scopes and what is in them, their links. The server checks such a
     write for shape, ids, membership, revisions and epochs, never for what its ciphertext says, so schema-valid random
     ciphertext is valid data as far as it can know. Every admin-listener write, the writes that change how the run's
     identities sign in, and the tenant's own configuration and membership are sent only malformed, a reason per entry.
   - **Links are inferred, not declared:** the contracts carry no `links`; the stateful phase infers them from the
     schemas and `Location` headers. Hand-kept links would be a second description of what the schemas say.
   - **The contracts as the server reads them.** The positive invocations read the contracts' own idioms spelled for a
     generator (base64 as whole quanta, a non-blank string, a nullable type that is not null, an integer within its
     generated type); the negative ones read the contracts as they are. A rule the server holds beyond these is the
     contract's to state, not the run's: in the schema where a schema can say it, else as a semantic status with a
     code of its own (`422`), never the shape's `400`.
   - **A second identity:** a member of the same tenant, of none of the owner's scopes. Every positive request the
     owner made in its scope and the server accepted is sent again as that peer and must be answered exactly as a
     scope that does not exist is (membership is the first guard, ADR 0042). Schemathesis' own object-level
     authorization check stays off: it needs both tokens in a file and calls a read a leak only on an equivalent body.
   - **Deterministic.** A fixed seed lives in the runner; a red re-runs to the same request, and the run's reports
     keep a `curl` line per failure. Changing the seed is a commit.
   - **A finding is fixed with a test in the ordinary suite** that fails on the defect, as ADR 0100 decision 11 asks
     of a pentest finding. A false positive, or a finding deferred to a CR of its own, is recorded in Schemathesis'
     baseline file with a `reason` per entry, which a guard holds; a **5xx is never** recorded there, and no entry
     expires by date — a gate turns red on a commit, never on a calendar day.

## Consequences

- **The critical path per push is `max(surface checks, build + lanes)`**, against the sum when the build waited on a
  second copy of the tests; a release is a promotion, minutes. Its estimate is the stage map's.
- **ADR 0057 § 1 and § 7 are replaced in part** (its header): a green push publishes an immutable snapshot, a release
  is its promotion, the image carries no `-SNAPSHOT`, and lanes L2 and L3 succeed the jar-level upgrade proof.
  **ADR 0090** follows: the gate is the push, the release verifies; no tiers, the lanes of decision 3. What is built
  and what is not is [release-and-deployment](../crosscutting/release-and-deployment.md) § Decided, not built.
- **The guard gaps run on their own trigger**, in full in CI, skipped locally while their inputs are unchanged since
  the last green full run; a guard holds the CI filter to the skip's inputs. The cost of the filter is the skip's: a
  forgery's anchor renamed outside those inputs shows at the next nightly run, not on the push that renamed it.
- **The remote comes when the owner judges veyq ready to publish, and it will be public.** Until then every stage's
  only run is local, by one command that reads the pipeline's workflow itself and names every job and step it did not
  run; where a stage's work sits in an action only GitHub runs, the workflow carries the host's stand-in. Local wall
  times say little about a CI runner's, so every budget stays an estimate until the first CI run.
- **Registry growth** is bounded by the retention rule; a snapshot per `main` commit is otherwise one image set per
  commit.

### Rejected

- **A release that builds again** (the `-SNAPSHOT` stamp kept in the image). The release would be bytes no test saw.
- **Testing only at the release.** A regression is found at the moment it costs most; the lanes run per push instead.
- **Gherkin (playwright-bdd, cucumber-js) or Gauge.** All three are maintained; none removes a layer here. The acceptance criteria already live in `docs/features` and
  `docs/coverage`, and a `.feature` file would be their second wording — two copies, one of which drifts.
- **Guard gaps on every push.** 90 % of the trust-chain's time spent proving that guards no commit touched still bite.
- **Keeping `ci.yml` and `container.yml` apart.** The second copy of the surface checks is a quarter of an hour on the
  critical path and proves nothing the first did not.
- **The API fuzzing inside the API end-to-end job.** That job runs close to its budget; a second workload in it would
  push it over, and a fuzz finding would read as a wire-sweep red.
- **The API fuzzing on lane L1, or only in the pentests.** The candidate's production rate limits refuse a fuzzer's
  volume, and a pentest runs by hand (ADR 0100 decision 1): a contract defect would be found weeks after its commit.
  The pentests point the same harness at the candidate instead.
- **Schemathesis as a Python library on the host.** It needs Python ≥ 3.11 and the
  scripts run on 3.9; the image carries its own interpreter and its own hash-pinned packages, the host none.
- **A random seed per run.** It finds more over time and makes a red that the next run cannot reproduce; a gate has
  to be deterministic. A seed is bumped by a commit when the owner wants a new sample.
