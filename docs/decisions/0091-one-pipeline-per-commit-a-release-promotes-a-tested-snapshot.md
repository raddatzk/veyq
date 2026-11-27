# ADR 0091 — One pipeline per commit, every stage on a budget; a release promotes a tested snapshot

- Status: Accepted
- Built: partly (not built: decision 3's lane L3, decision 5's rerun of moved lanes — a promotion refuses an input it
  cannot compare, and lane L2's predecessor when it moved, instead — and the snapshot retention job, decision 6's
  archive and L3, decision 7; lane L2 is built as decision 6's amendment of 2026-11-26 decides it —
  `scripts/run_stack_upgrade.py`, checks.yml's `stack-upgrade`, attested per leg with the release it upgraded from and
  required by the promotion, first attested by the Publish of `d5ce223da` (run 46018703012: v1.318 and v0.2480);
  decision 5's snapshot —
  weekly and by dispatch since its amendment of 2026-11-07, per green push before — its attestations, its `snapshot-`
  tag, the staging retention (first run 2026-11-23) and the promotion `release.yml` are built, and since its amendment of 2026-11-22 the release draft Publish writes — v1.312
  and v1.318 were released from such drafts)
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

2. **One pipeline per commit, and nothing waits that need not.** One workflow, `checks.yml` ("Checks").
   The guards, the surface checks, the races, the API end-to-end run, the API fuzzing **and the image build start
   together**; the build does not wait for the tests, because nothing is published until every stage is green — the
   gate job (`green`) needs every other job of the file, and a publish needs that run's gate. The stack lanes need the
   build. The surface checks run once. The guard gaps are not in it (decision 4), and `trust-chain.yml` stays
   separate and unfiltered, because a docs-only commit is validated there and nowhere else.
   - **Amended 2026-11-07 (owner, cr-workflows-split-pipeline-callers): the pipeline publishes nothing; Publish signs
     what a Checks run staged.** Until then the pipeline was `ci.yml`, which was also the weekly and the dispatched
     publish (and, for a few hours that day, `pipeline.yml`, a reusable workflow that Checks and Publish both called).
     Now `checks.yml` runs on every push and pull request; a push to `main` or `release/<major>.x` **stages** its images
     — the per-arch `build-<sha>-<arch>` tags, unsigned, pulled by digest by its lanes, whose green legs record what
     they installed. **Publish** (`publish.yml`, weekly and on a dispatch) runs no pipeline and builds nothing: it finds
     HEAD's newest Checks push run, refuses unless that run concluded `success` with its gate and every leg of every
     lane's matrix green (L1 on both architectures), and then signs and names exactly the digests that run staged and its lanes recorded
     (decision 5). The signing is Publish's own: keyless signing names the file that holds the signing job. A pull
     request's run holds no write (`build` runs on a push alone and is the one job with `packages: write`; a pull
     request builds the same matrix and steps as `build-local`).
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
   - **Pull request** (Checks): everything in 1 except mutation's scope rule, L1 on amd64, nothing published. A pull
     request never writes to a registry.
   - **Push to `main` or `release/<major>.x`** (Checks): everything, all three lanes on **both** architectures (what is
     published for an architecture must have passed on it), its images staged; nothing published since decision 5's
     amendment of 2026-11-07.
   - **The weekly schedule and a dispatch** (Publish): the snapshot of HEAD's green Checks push run — of a docs-only
     HEAD's newest tested ancestor's (decision 5, amended 2026-11-08) — in
     minutes, and on the schedule the deletion of staging images older than 30 days.
   - **Guard gaps** run when what the guards are made of changes, and nightly on `main` — they test the guards, and a
     product commit changes no guard.
   - **Release**: decision 5's promotion; the tests it needs already ran.

5. **Build once, promote.** A green run of the pipeline publishes its images as a **snapshot** — weekly and by
   dispatch, never per push (amended 2026-11-07, below) — `<major>.<minor>-SNAPSHOT` and
   `sha-<commit>` on the exact digests the lanes tested — signed, with each lane's result attached as a signed
   attestation (lane, architecture, outcome, and its **inputs**: the predecessor digest for L2, the list of backups
   for L3, and the hash of the release-asset set it installed). A snapshot is written once per commit and never
   replaced. **A release is a promotion**: a dispatch names a commit (default: the tip), its snapshot's attestations
   are verified, and the same digests get `<major>.<minor>`, `<major>`, `latest` (from `main`), a release signature
   and the git ref `v<major>.<minor>`; then the **immutable GitHub Release** is created from the asset set
   ([ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §3). Nothing is rebuilt.
   - **A release is started from GitHub's release page, not by a dispatch** (owner 2026-11-06). Every snapshot also
     sets the git tag `snapshot-<major>.<minor>` on its commit, from a job of its own that holds `contents: write` and
     does nothing else; the retention job below deletes a snapshot's tag with its images, so the release page's tag
     list offers exactly the snapshots that can still be promoted. The owner saves a **draft** release on one of them,
     with the notes in their words, then presses *Run workflow* on `release.yml` — a dispatch with no required input,
     not the commit-naming one: exactly one draft may exist, on a `snapshot-` tag, and it promotes that one — none,
several, or one on another tag is refused (`scripts/promote.py` check 1). A saved
     draft starts no workflow by itself (GitHub runs none for a draft's `created`, `edited` or `deleted`, measured
     2026-11-06 on the events page), and its publication comes too late to attach anything (owner's choice of trigger,
     2026-11-06). The promotion refuses unless the tag
     is a `snapshot-` tag whose version is its commit's, verifies and completes the attestations as below, then moves
     the draft to `v<major>.<minor>`, attaches the asset set and publishes it — publishing last, because an immutable
     release takes no asset once it is published. A refused promotion leaves the draft unpublished and writes why
     into its notes. A snapshot's tag is never `v…`: that name says "released" (ADR 0057 §2), and every tag watcher —
     a Renovate `github-tags` lookup included — would read each snapshot as a release.
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
   - **Amended 2026-11-07 (owner): a snapshot is published weekly and by dispatch, no longer per push.** Once
     Dependabot opened its pull requests, every merged bump was a green push, so every one became a snapshot — three
     images, a chart, signatures, attestations and a `snapshot-` tag — far more than anyone would promote or try. Every
     push still runs the whole pipeline (decision 2) and publishes nothing; the publish is its own workflow,
     `publish.yml`, on two events only:
     - its **weekly `schedule`** (Monday 03:17 UTC). GitHub runs a schedule on the default branch alone, so it
       publishes `main`'s HEAD and never reaches a `release/<major>.x` branch;
     - a **`workflow_dispatch`** — the owner's *Run workflow* in Actions, "a snapshot now" — on `main` or
       `release/<major>.x` (red on any other branch). A fix on a release branch gets its snapshot this way.

     Neither runs the pipeline (decision 2's amendment of the same day): both publish HEAD's newest Checks push run
     when it is wholly green, from the digests that run staged and the records its lanes left (kept 30 days as the run's
     artifacts), and refuse, red and named, a run still going, red, or cancelled by hand — Publish never starts Checks
     (owner). The signing identity is `publish.yml`'s (it was `ci.yml`'s before, and a snapshot signed under it is not
     promotable, owner 2026-11-07); each attestation names the Checks run its lane ran in. **Neither publishes a HEAD
     that already carries a `snapshot-` tag**: such a run writes nothing, with a notice saying so, because a snapshot is
     written once and never replaced. The tag is read from Publish's own checkout (`fetch-depth: 0` fetches every tag):
     no registry, no network, and the tag is the last write of a complete publish, so it is exactly "this commit has a
     promotable snapshot". A publish that lost its tag (its `snapshot` green, its `snapshot-tag` failed) is not seen as
     published: the next run is refused by the registry's write-once check — red, which is the signal to re-run the
     old run's failed `snapshot-tag`. A rule read from commit messages was rejected: a message is a weak source of what
     a commit is. Publish has a concurrency group per ref apart from Checks', so two publishing runs go one after the
     other and the later one sees the earlier one's tag. A snapshot is therefore "the newest green `main` of that week,
     or the commit the owner asked for" — no longer "every green commit". **Staging images** are deleted by the weekly
     run 30 days after their push unless their commit is a branch tip; a staging index whose commit has a snapshot goes
     alone, the snapshot's own index naming its children (owner: on from the first run). `release.yml`'s promotion is
     unchanged.
   - **Amended 2026-11-08: a docs-only HEAD publishes its newest tested ancestor.** Checks skips a push that changes
     only what its `on.push.paths-ignore` names (`**/*.md`, `docs/**`, `site/**`, decision 2: a doc change cannot break
     a surface check or an image), so such a commit has no Checks run — and Publish, which demanded a run for exactly
     HEAD, was red, weekly and on a dispatch, whenever `main`'s tip was docs-only (measured: Publish run 42484798393 on
     `3ae1ae01c`, whose two newest commits changed only `site/**` and `docs/**`, above `865ba64e0` and its green Checks
     run 42481494616). Now Publish resolves the **publishable commit** C (`scripts/snapshot.py checks-run`): HEAD when
     HEAD has a Checks push run; otherwise the newest first-parent ancestor that has one, accepted only if every commit
     after it up to HEAD changes nothing but paths that filter ignores — read from the checked-out `checks.yml`, never a
     copy, each commit judged by its own diff — and refused, named, when a commit in between changes anything else or
     no run is found within 50 commits. C's run is held to the verdict above, unchanged. The snapshot is then **C's,
     wholly**: its version is C's tree release, its images are named `sha-<C>`, the staging tags read are C's, the
     attestations name C, the tree the chart is packaged from is C's, and the git tag `snapshot-<major>.<minor>` is set
     on **C, the tested commit** — never on the docs-only HEAD above it, which no run tested. Every name says what was
     tested, so this holds even if a docs-only commit could change shipped bytes; today it cannot (measured 2026-11-08:
     `git ls-files '*.md'` outside `docs/` and `site/` lists only READMEs, agent and contributor notes and `changes/`,
     and no Dockerfile copies a `.md`, `docs/` or `site/`). A C that already carries its `snapshot-` tag publishes
     nothing, as HEAD did. Rejected: running Checks on
     every commit (the filter is what keeps a doc change from costing a full pipeline), and tagging HEAD (the tag would
     name a commit that no attestation names, and the promotion counts the version from the tagged commit). The cost:
     a snapshot can lag `main`'s tip by its docs-only commits, and a C older than the 30 days its run's artifacts are
     kept cannot be published — re-run its Checks run, or push.
   - **Amended 2026-11-22 (owner, cr-release-draft-from-publish): Publish writes the release draft; the release stays
     the owner's click.** The owner asked for the release to be half-automatic: the draft is there every Monday, the
     owner reads it and runs `release.yml`. A fully automatic release was weighed and declined by the owner — the
     *Run workflow* click is the one deliberate "this is fit for operators", and a draft starts no workflow anyway
     (GitHub runs none for a draft's events, and a published immutable release takes no files, so the draft cannot be
     the trigger). So a snapshot of **`main`** gets a job `release-draft` after its tag, holding exactly `contents:
     write` (drafts are listed and written only with push access) and `pull-requests: read`, signing and tagging
     nothing; `scripts/release_notes.py` decides and the job's one create-or-edit line writes, never `draft: false`.
     The notes come from the **pull requests**: since features land on `main` only as squashed pull requests once
     releasable, each commit since the last `v<major>.*` tag is one pull request, and each carries a `## Release note`
     section in the release's words, or `none` (`release-note.yml` checks every pull request; Dependabot's are exempt
     and become one line). Rejected: commit titles (engineering language, never what the notes said) and a model
     called in CI (a closed vendor and a secret in the release path, for text the owner reads anyway). `release.yml`'s
     rule of exactly one draft is kept by the job: it moves its own **untouched** draft (a hash marker over its body)
     onto the newer snapshot, creates one where none exists, and leaves an edited one — by a person, or carrying the
     promotion's refusal — and any second draft alone, with a notice. A fix release on `release/<major>.x` and the first
     release of a major are drafted by hand. With it, a commit that carries a **release tag** `v<major>.<minor>` counts
     as published too: `release.yml` deletes the snapshot tag once it released the commit, so the schedule of
     2026-11-16 made the already released `b7dda2de` a snapshot again (`snapshot-1.247`, beside `v1.247`), and a draft
     would have followed it.
     **Refined 2026-11-23 (owner, cr-release-draft-from-publish):** the first draft (run 45076081333) named a pull request
     under **Needs a note**; its note was then written in the pull request, but no run could carry it into the draft — the
     job ran only after a *new* snapshot tag, and a commit that has its snapshot publishes nothing. So the job also runs
     when `checks-run` finds the commit already carrying its unreleased snapshot (`kind == 'none'`, no `v` tag;
     `snapshot.py`'s `draft_version`), and a dispatch on `main` refreshes the untouched draft; `release_notes.py`
     refuses unless `snapshot-<version>` names the commit, and the job's whole `if:` is pinned by
     `validate_release_identity.py`. And the promotion refuses a draft that still lists anything under **Needs a note**
     (`promote.py` check 1): until then such a draft was publishable as it stood.
     **Refined 2026-11-26 (owner):** the **schedule waits** for a Checks run still going. GitHub starts a schedule late and
     at no fixed hour: on 2026-11-23 it ran at 12:56, not 03:17, minutes after a merge, and `checks-run` refused HEAD's
     `pending` Checks run (run 45179033821) — the week had no snapshot and no draft until a dispatch. Now the
     schedule's `checks-run` re-reads that run every minute for up to 120 minutes (`snapshot.py --wait-minutes`,
     `await_completion`; the job's budget 130) and judges it once completed; a dispatch still refuses at once, the owner
     being there to run it again. Rejected: falling back to an older green commit (the snapshot would be of something
     other than `main`'s tested HEAD, and the next week would repeat it) and a second cron (both fire late together).
     **As built (2026-11-26):** the draft is written by a job of its own, `release-draft`, not a step of `snapshot-tag`
     — that job is the one ref writer and holds `contents: write` alone (owner 2026-11-06), and the draft needs
     `pull-requests: read` besides. A pull request's note is checked by a workflow of its own, `release-note.yml`
     (advisory, no required check), so editing a body re-runs one small job and never the pipeline. No lede is
     generated: summing several pull requests up in one sentence is the owner's judgement while reading the draft, and
     v1.312 and v1.318 went out without one. Measured on GitHub: `commits/<sha>/pulls` returns the squash-merged pull
     request (run 45076081333); a draft's body reads back from the API as untouched; the untouched draft moves to the
     next snapshot with its notes regathered (run 45153047948, nine pull requests); a draft Publish wrote is promoted
     (v1.312, v1.318). **Not observed on GitHub**, held by the selftests alone: a Monday with nothing new writing no
     draft (a released HEAD's `draft_version` is empty, `snapshot_selftest.py`), a dispatch refreshing the draft of a
     snapshot already made (`kind == 'none'`), and a late schedule waiting out a running Checks run.

6. **The backup archive.** Every release publishes the L1 run's capture (ADR 0056's tool, test data only) **with its
   object pool** (`--objects-to`: a capture's store leg is only its index, ADR 0056 §3) as a signed OCI artifact
   beside its images. L3 restores every archived backup of the current major — within a major, everything older must
   open ([ADR 0085](0085-one-major-moved-on-a-yearly-train.md)) — and the backup of the candidate's **major entry**
   (ADR 0085 decision 10), the only release [ADR 0096](0096-veyqctl-manages-an-instance-from-immutable-releases.md) §5
   enters a new major from. Before the first release the archive is empty, L2 upgrades from the previous snapshot, and
   both inform and gate nothing, as ADR 0057 §7 says for the upgrade proof.
   - **Amended 2026-11-26 (owner: compatibility is between releases, and L3 restores per schema state; the rest
     decided in refinement, inbox #453 and #461): what L2 and L3 write and read, where the archive comes from, and
     which backups L3 restores.** An operator only ever runs a release (ADR 0096) and may upgrade from any release of
     the major, so what must hold is release → candidate, never commit → commit. Every commit is still tested, because
     every commit on `main` may become the next release (decision 5); what a lane starts from is a release.
     1. **The write half is the starting release's own; the read half is the candidate's.** Both lanes write with the
        API upgrade seeder of the starting release's tree (`upgrade.a-seed.e2e.test.ts`, the pair the jar-level proof
        runs, present unchanged in every release since v0.2480) against that release's own signed images, and read
        with the candidate's reader (`upgrade.b-read.e2e.test.ts`) against the candidate, the versioned record
        (`upgradeRecord.ts`) between them. One Playwright spec of the candidate's then opens the seeded content in a
        browser. Not a new browser write half, and not decision 3's split of the scenarios: a browser write half
        would exist only from the next release on, so every release before it would be written by today's test code
        against an older interface — the half that breaks would be the test's, not the product's. The seeder uses
        nothing only the e2e profile serves and makes three requests on the per-IP budget of 60 a minute (measured
        2026-11-26 by a static trace of the suites against the rate-limit annotations), so it runs on the production
        recipe unchanged.
     2. **L2 upgrades with the starting release's `veyqctl`**, the tool its operator has installed — which is also the
        lane ADR 0096's minor promise (decision 9) was missing. It runs from the newest release and, in a leg of its
        own, from the major entry, crossing the major in place as an operator would. After the read it rolls back to
        the backup the upgrade took and reads again with the starting release's own reader, so the recovery the
        upgrade names on a failure has been run.
     3. **The archive is written by a workflow of its own after the promotion, not taken from L1.** It installs the
        published release, writes with that release's seeder, takes `veyqctl backup` with its object pool and its
        kept-apart key, and publishes the result as an OCI artifact beside the release's images, signed keylessly
        under that workflow's identity, which L3 verifies. One path for every release, including the ones released
        before it: none of those has a capture (L1 never takes one, and the jar-level proof's are kept seven days),
        so each is written once by the same workflow, dispatched with its tag. Every release is archived, not only
        the ones L3 restores, so the selection below can change without a backup missing.
     4. **L3 restores one backup per schema state, not one per release.** A release's schema state is its highest
        migration, read from its own `scripts/applied_migrations.sha256`. Within the candidate's major L3 restores the
        oldest release of each state, plus the major entry — derived from the tags and their pin files, never listed.
        Flyway runs the same migrations in the same order from any release of a state, so the state, not the
        release, is what the database path depends on: on 2026-11-26 the eighteen releases fell into four states
        plus the entry. The cost grows with a major's migrations, not its releases, and resets with each major, where
        only the entry carries over. **Its limit:** a release that writes a new data format without a migration is
        covered only at the edges of its state — the oldest release by L3, the newest by L2. A format first written
        by a release in the middle of a state and changed again before the state ends is never restored. A data
        format version of its own would close that; it is not decided.
     5. **When.** L2 on every pull request and push; L3 on a push, its backups spread over as many legs as decision
        1's budget needs, not on a pull request, which keeps L2. A backup the selection names that is not archived is
        red — a defect, never a skip. **Both on amd64 alone** (amended 2026-11-27, owner): what they prove is the data
        path — Flyway's SQL on the one pinned MariaDB, the dump through the pinned vendor image, ciphertext — and no
        architecture changes it; what an architecture does change (the image's start, its native code, the features)
        is lane L1's, on every architecture the build publishes. Decision 4's rule that what is published for an
        architecture has passed on it holds through L1; a data-path lane runs on a published architecture, never on
        one L1 did not boot (`scripts/validate_release_identity.py`, `snapshot.py`, `promote.py`).
     - **L2 as built (2026-11-27).** A leg with no release to start from still records — the candidate's asset set
       and `predecessor: null` — so the predicate expects every leg of the matrix and a promotion tells "had none"
       from "lost". The promotion compares each leg's **tag** with the one it resolves now
       (`run_stack_upgrade.py --leg X --resolve` at the snapshot's commit); the digests beside it are recorded, not
       compared, since a tag names an immutable release. The rollback of item 2 runs after a green read only: a red
       upgrade leaves the instance as `veyqctl` left it, and the recovery `veyqctl` names on that path is not run
       (inbox #869). Measured on GitHub: both legs green on pull request 111 and on the push of `d5ce223da`, and
       that commit's Publish (run 46018703012) attested L2 on amd64 with v1.318 and v0.2480, verified with
       `cosign verify-attestation` under `publish.yml`'s identity.

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
- **ADR 0057 § 1 and § 7 are replaced in part** (its header): a green run publishes an immutable snapshot (weekly or
  by dispatch, decision 5's amendment), a release
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
- **Registry growth** is bounded by the retention rule and, since decision 5's amendment, by the trigger: about one
  snapshot a week of `main`, plus the ones the owner dispatches, instead of one per green commit.

### Rejected

- **A release that builds again** (the `-SNAPSHOT` stamp kept in the image). The release would be bytes no test saw.
- **Testing only at the release.** A regression is found at the moment it costs most; the lanes run per push instead.
- **Gherkin (playwright-bdd, cucumber-js) or Gauge.** All three are maintained; none removes a layer here. The acceptance criteria already live in `docs/features` and
  `docs/coverage`, and a `.feature` file would be their second wording — two copies, one of which drifts.
- **Guard gaps on every push.** 90 % of the trust-chain's time spent proving that guards no commit touched still bite.
- **Keeping `ci.yml` and `container.yml` apart.** The second copy of the surface checks is a quarter of an hour on the
  critical path and proves nothing the first did not.
- **Publish running the pipeline itself** (built for a few hours on 2026-11-07, the owner reversed it the same day):
  a snapshot took the pipeline's 40 minutes again, re-testing a commit Checks had just tested, and a snapshot run that
  went red on a flaky leg left a green commit unpublished. Publishing the Checks run's own staging keeps the published
  bytes the tested ones and takes minutes.
- **A Publish triggered by the Checks run** (`workflow_run`): a snapshot per green push, which is what the amendment of
  decision 5 removed. Publish reads the run by the commit instead, when the schedule or the owner starts it.
- **The API fuzzing inside the API end-to-end job.** That job runs close to its budget; a second workload in it would
  push it over, and a fuzz finding would read as a wire-sweep red.
- **The API fuzzing on lane L1, or only in the pentests.** The candidate's production rate limits refuse a fuzzer's
  volume, and a pentest runs by hand (ADR 0100 decision 1): a contract defect would be found weeks after its commit.
  The pentests point the same harness at the candidate instead.
- **Schemathesis as a Python library on the host.** It needs Python ≥ 3.11 and the
  scripts run on 3.9; the image carries its own interpreter and its own hash-pinned packages, the host none.
- **A random seed per run.** It finds more over time and makes a red that the next run cannot reproduce; a gate has
  to be deterministic. A seed is bumped by a commit when the owner wants a new sample.
