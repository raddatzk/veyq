#!/usr/bin/env python3
"""Guard that a veyq release has an identity, that the identity comes from the tree, and that every artifact
naming a release names a shape the pipeline actually emits (ADR 0057).

**What it asserts.** Fourteen things (11 retired from this pipeline with the release kind, below), in the order a
snapshot happens:

  1. **`VERSION` exists at the repository root and holds the major alone.** With the history it *is* the release
     identity (ADR 0057 §1): the minor is the number of commits since `VERSION` last changed, so a checkout
     answers what it is, with no network call and no registry — and no tree can hold the minor.
  2. **The publish workflow derives the version from the tree, offline.** The `version` job's compute step is
     *executed* here, inside a throwaway git repository this guard builds — `VERSION` changed twice, then a
     known number of commits, an older commit carrying an older `snapshot-` tag, plus a shallow clone of it and a
     clone whose HEAD carries its own `snapshot-` tag — so the minor is asserted against a known count, never
     against this repository's own history, which moves. Scenarios: the schedule on `main` and a dispatch on `main` and
     on `release/<major>.x` (kind `snapshot`); the same three on a HEAD that already carries its snapshot, a push to
     `main` and to `release/<major>.x`, a pull request and a local run (`runner.environment` `local`, as
     `scripts/run_pipeline.py` sets it) of a push to `main` and to a feature branch (kind `none`), every one stamping
     `<major>.<minor>` and never `-SNAPSHOT` (ADR 0091 decision 5) — and, each must FAIL, a push, a schedule or a
     dispatch on a branch that disagrees with the tree or is not a release branch, and a shallow checkout. Executing it is the only
     honest way to assert what a publish *would* stamp; reading it would only
     assert what it looks like. The step is also held to declaring no network tool at all (a `git` that talks to
     a remote included), because a version a registry decides is not an identity (ADR 0057 § Rejected), and to
     receiving each input the scenarios set from the expression a run sets it from. `scripts/release_assets.py`
     counts the release a second time, to name the release's assets, and is held to the step's count on the same
     history and to refusing the shallow clone.
  3. **Only a publishing run writes; a snapshot refuses to replace itself, and its one git ref is `snapshot-…`.**
     Every write in the file — found by what a step does (a login or build-push action, or a `run:` that pushes, logs
     in, signs or attests), never listed — is gated on `needs.version.outputs.kind == 'snapshot'` by its step, its
     job or its push input, so a pull request (Dependabot's included) and a local run write nothing.
     `workflow_dispatch` takes no input: a dispatch is a snapshot now, by hand, and a release is a promotion in a
     workflow of its own. The `snapshot` job holds `packages: write` and `id-token: write` and never `contents: write`; the
     `snapshot-tag` job needs it, is gated the same way and holds exactly `contents: write`; a ref write (`git push`, or
     `gh api … git/refs`, the way the tag is created) runs in that job alone, as `snapshot-${VERSION}` and never a `v…` name, and no step writes a GitHub Release. The `snapshot` job's
     refusal (`scripts/snapshot.py check-unpublished`) and the tag step are keyed on the *tree-derived* version, force
     nothing, and the refusal comes before the job's first write. The `build` job stamps the same value
     into the image as `VEYQ_VERSION`, which is what the running instance later reports — and every build-arg it
     stamps is declared by the target stage each matrix entry publishes, and by **no other stage** of that entry's
     Dockerfile (nor before its first `FROM`) — per entry: a build step gated `if: matrix.image == '<x>'` (or `!=`)
     stamps only the entries it builds, so the tools image's major entry (ADR 0098) is held to the tools image alone,
     and every entry must receive `VEYQ_VERSION` from some step. The stage that compiles the web client is served to anyone before any
     login, so a stamp it could read is a release on the pre-auth surface (ADR 0057 §4); a stamp the target does not
     declare is one Docker drops without a word. Both found by stage, from the build matrix, never listed.
 12. **The image's labels name the build that made it.** Each published target stage sets
     `org.opencontainers.image.version` and `.revision` from `${VEYQ_VERSION}` and `${VEYQ_REVISION}`, after it
     declares both, and its `.title` is the matrix's image name; the `build` job stamps `VEYQ_REVISION` into every
     entry; and no step of the file passes a label to a build (`labels:` on a build-push action, `--label` on a
     `docker build`), because a passed label overrides the Dockerfile's. Its premise is BuildKit's: a `LABEL` is image
     config, never a cached layer, so a fully cached build still carries the stamps it was given — measured
     2026-10-24 on the admin image (round 2 of the heavy stage: every layer cached from 2026-10-23, `Created`
     2026-10-23, `revision` the commit built), and re-measured on demand by `scripts/image_identity_cache_proof.py`,
     which builds each target at two revisions on a warm cache and also refuses an OCI label the base image lends.
  9. **The snapshot waits on every stage** (ADR 0091 decision 2). The workflow is the one pipeline per commit, and
     its image build starts beside the tests instead of after them — which is sound only while nothing it builds is
     published before every stage is green. So every other job of the file is in the `snapshot` job's `needs:`, found
     by reading the file's jobs, never listed — except the jobs that come after it (they need it: its git tag); and
     when its `if:` calls a status function (`always()`, `failure()`, `cancelled()`) — which lets it run past a failed
     or skipped need — the condition requires `needs.<job>.result == 'success'` of every need, the one exception a job
     that is literally `if: false`, whose `skipped` it may accept alongside `success` (the disabled upgrade proof).
 10. **The snapshot waits on lane L1** (ADR 0090 decision 2). Assertion 9 holds the snapshot to the jobs the file
     has, and cannot see one that is gone; so the job that runs `scripts/run_stack_e2e.py` — found by what it runs,
     never by its name — must exist, need `build`, run on every architecture the build matrix publishes, and be in
     the snapshot's `needs:` with `needs.<lane>.result == 'success'` among its `&&` terms.
 13. **Each lane's result is attested by the snapshot, never by the lane** (ADR 0091 decision 5). The lane's job
     records each green leg (`scripts/snapshot.py lane-result`, gated like every write) and holds no `id-token`; the
     `snapshot` job assembles the predicates once (`scripts/snapshot.py predicates`) with `--arch` and `--shard` equal
     to the lane matrix's `arch` and `domain`, read from the file, and runs `cosign attest --type "$PREDICATE_TYPE"`
     with that variable set to `scripts/snapshot.py`'s `PREDICATE_TYPE`, after the predicates and after every image
     signature.
 11. **(Retired here.)** A release's one immutable GitHub Release, created last (ADR 0096 decision 3), left this
     pipeline with the release kind: a release is a promotion of a snapshot in a workflow of its own, and assertion
     14 holds it there. What stays of it here: **the
     cosign that signs is the line operators verify with** (inbox #474): exactly one `sigstore/cosign-installer` step
     in the file, in the `snapshot` job before every step that runs `cosign`, with `cosign-release` an exact
     `v<major>.<minor>.<patch>` no older than `src/tools/veyqctl.py`'s `COSIGN_MIN_VERSION` — never the installer's
     default, which lags — and no `cosign` line anywhere passes the deprecated flag that chose the bundle format
     (cosign 3 writes the Sigstore bundle by default, and only its `=false` spelling still writes the legacy one).
 14. **The promotion** (`.github/workflows/release.yml`, ADR 0091 decision 5): the file the release identity names
     exists; it is started by `workflow_dispatch` alone, whose one input is the boolean `unscheduled_major` (default
     false); it runs in concurrency group `veyq-release`, never cancelled, under `permissions: {}`, and its five jobs
     hold exactly — `check` contents write (draft listing) and packages read, `promote` packages and id-token write,
     `publish` contents write, `channels` packages write, `refused` contents write; `promote` needs `check`, `publish`
     both, `channels` runs only once `publish` says published, `refused` never after it. No step builds (a build-push
     action, `docker build`, `buildx build`, a Gradle or pnpm run); only `publish` and `refused` write to the repository
     or a GitHub Release; `<major>` and `latest` are named in `channels` alone. `publish` publishes the draft in one
     step (`draft=false`), after it verifies the files against `veyqctl`'s own identity (`scripts/promote.py
     release-identity`), sets the git tag, moves the draft and attaches every file — after it only the snapshot's tag
     and a replaced draft are deleted. `promote` produces the files with the snapshot commit's own producer (the
     `snapshot` worktree), holds them to the plan (`scripts/promote.py assets`), and only then signs exactly `veyqctl`
     and the manifest into the producer's reserved bundle names; it packages the chart from the snapshot's commit as
     `$VERSION` and signs it. Every job that runs cosign installs `ci.yml`'s cosign first, the Helm pin is `ci.yml`'s,
     the file states no certificate identity of its own, and no other workflow writes a GitHub Release. What
     `scripts/promote.py` decides — every refusal — is `scripts/promote_selftest.py`'s, which `check` and `ci.yml`'s
     `guards` run.
 15. **A job that can write installs only hashed wheels.** Every `pip install` (also `python -m pip install`) of every
     `release.yml` job, and of every `ci.yml` job whose permissions hold a `write` (`id-token`, `packages`, `contents`,
     or `write-all`), carries `--require-hashes`, `--only-binary :all:` and `-r <file>`; each such file exists and every
     requirement in it is `<name>==<version>` followed by its `--hash=sha256:` values (`scripts/requirements-release.txt`).
     What PyPI serves unpinned at install time runs with the job's token — in `promote` the release identity's.
     `ci.yml`'s read-only jobs are out of scope: they reach only their own runner.
 16. **No job relies on the implicit `success()` behind a job that can be skipped.** In `ci.yml` and `release.yml`,
     a job whose needs reach a job with an `if:` (directly, or through the chain) states a status function in its own
     `if:` (`!cancelled()` or `always()`, never `success()`) and, for every such need, `needs.<job>.result == 'success'`
     (or a `contains(fromJSON(…), needs.<job>.result)`) as an `&&` term — GitHub's implicit `success()` looks at the
     whole chain, so a skipped job anywhere upstream skips everything after it. `!cancelled()` is the one negation the
     gating model of assertion 3 accepts.
 17. **Only the weekly schedule and a dispatch publish, and never twice for one commit** (ADR 0091 decision 5, owner
     2026-11-07). `ci.yml` triggers on `push`, `pull_request`, a `schedule` with at least one `cron` and
     `workflow_dispatch`. Of assertion 2's executed scenarios, the events that came out `kind=snapshot` are exactly
     `schedule` and `workflow_dispatch` — derived from what the step wrote, not from the expectations table, so a push
     that publishes is red even with the table edited to expect it — and no scenario whose HEAD already carries a
     `snapshot-` tag publishes, while the clone whose only `snapshot-` tag sits on an older commit does (a check
     that reads "any snapshot tag exists" would stop every snapshot after the first). Assertion 3 holds every write to
     that `kind`, so a push run cannot reach one. And the `concurrency.group` — *evaluated* for each event on `main`
     and `release/<major>.x`, never matched as text — puts the schedule and a dispatch of a ref in one group and a
     push or a pull request in another, per ref, never cancelled in progress: two publishing runs then go one after
     the other, so the later one sees the earlier one's tag, and a push neither waits for a snapshot nor gets a
     pending scheduled run cancelled by queueing behind it.
  8. **The chart is the snapshot's third artifact** (ADR 0064). The refuse step checks `--image` for every published
     image (the build matrix) AND `--chart charts/<name>` (`Chart.yaml`'s `name`) under the workflow's own registry; the
     step that runs `helm package` sets `--version` and `--app-version` to the tree-derived version's `-SNAPSHOT`
     name (the chart's `appVersion` is the image tag its values default to) and pushes to `oci://ghcr.io/<owner>/charts`;
     a `cosign sign` step names `charts/<name>`; and the
     chart's default image repositories are exactly the upstream images the Run area's verification page
     (`site/src/content/docs/run/verify-a-release.md`) verifies by digest — `image.repository` the chart's own
     name, `admin.image.repository` its `-admin` sibling, `backup.image.repository` its `-tools` one (ADR 0098). And that page verifies the chart itself by digest (assertion 7 collects `ghcr.io/<owner>/charts/<name>`
     tokens as it collects the images', and holds them to a digest the same way).
  4. **The release carries every file its recipe reads** (ADR 0096). `scripts/release_assets.py` derives the
     recipe set — the production compose file, `.env.example`, and every file the compose file reads relative to
     itself, in each spelling Compose has for that (`volumes` short and long-form `type: bind`, `env_file`,
     `label_file`, top-level `configs`/`secrets` `file:`) — and refuses what it cannot derive (`${…}` in such a
     path, `../`, a directory in it, a name GitHub would rename, `include:`, `extends`). Here that derivation is run
     on the tree, its refusals are this guard's reds, and the producer's carry step is run into a scratch directory,
     where every derived file must be, byte-identical to the tree (the `.env` template under its asset name,
     `env.example`). Every `*_ASSET` name `src/tools/veyqctl.py` fetches must be one a release has — the producer's
     files, the manifest, or one of the two bundle names the producer reserves and a release's publish writes (the
     promotion, ADR 0091 decision 5)
     — and its `MANIFEST_BUNDLE_ASSET`, the file it verifies the manifest against, is the manifest's bundle by name.
     The derived list is printed on the OK line. Plus: every veyq image
     tag written down in a tracked deployment file (`TAG_SCAN_DIRS`: `infra/`, lane L1's overlay, the API fuzzer)
     or on the Run pages (`site/src/content/docs/run/`, where the operator procedures live) matches a tag the
     pipeline emits.
  5. **Every service in the release recipe runs an image the operator can PULL.** No service in
     `infra/docker/docker-compose.prod.yml` carries a `build:` stanza, and every veyq-namespaced image it names is one
     `.github/workflows/ci.yml` actually publishes — the set is read out of that workflow's build
     matrix, never listed here (§ *a guard derives its anchor set from the code*). A recipe that builds is a
     recipe only `docker compose` can deploy: Compose hides it by building on the operator's host, and
     [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)'s Helm chart cannot, because a
     chart has nothing to build with.
  6. **The production project name is a property of the compose file, not a rule stated in prose.**
     `infra/docker/docker-compose.prod.yml` declares a top-level `name:`, and no file of durable state instructs
     `-p <that name>` / `--project-name <that name>`. Compose derives a project from the compose file's
     *directory* when the file names none, and both compose files sit in `infra/docker/` — so a production procedure
     that depends on an operator remembering a flag is one shell away from addressing the wrong stack. The two
     halves are one assertion, not two: dropping the flag is safe only *because* the file carries what the
     flag used to say, and re-introducing the flag beside the key is how the two start to disagree.
  7. **The operator's documented signature check is exactly as narrow as what signs a RELEASE** (ADR 0057 §3, ADR
     0091 decision 5). Every `cosign verify` and `cosign verify-blob` (the one for `veyqctl` itself, ADR 0096
     decision 3) on `site/src/content/docs/run/verify-a-release.md` pins GitHub's OIDC issuer and one certificate
     identity; that identity **accepts** the release workflow (`RELEASE_WORKFLOW`, `.github/workflows/release.yml`)
     on every branch assertion 2's scenarios turn into a publish, and **rejects** `ci.yml` — the snapshot identity —
     on each of those branches (a snapshot and its release are one digest, so accepting it verifies every snapshot as
     a release), every other workflow in `.github/workflows/`, non-release branches (which the version step must
     refuse as well), pull-request and tag refs (a `snapshot-` tag's included), another owner, a repository whose name
     only starts with ours, and the right identity quoted inside another URL. Every image the build matrix publishes is verified, by
     digest and never by tag, `veyqctl` itself is verified with `cosign verify-blob`, and so is the manifest — `cosign
     verify-blob --bundle <release_assets.MANIFEST_BUNDLE>` on `manifest.json`, before its digests are read (inbox
     #469) — and the workflow states no identity of its own. The upstream is the chart's
     `home`, not the workflow's `github.repository_owner`, which is whoever runs it — a fork included. The
     operator's tool runs the same check (`veyqctl verify`, `install`, `upgrade`, ADR 0096): `src/tools/veyqctl.py`'s
     `COSIGN_OIDC_ISSUER` and `COSIGN_IDENTITY_REGEXP` are held to the same accepts and rejects, and to be exactly
     the identity the page documents, so the page and the tool can never state two.

**The defect that bought it.** All of it was live when this guard was written (2026-08-11), and the first run
was the discovery: there was no `VERSION` file at all, so the publish workflow's (then `container.yml`) first command
(`tr -d '[:space:]' < VERSION`) died and **no image had ever been published** — while `infra/.env.example` told
the operator to pin `VEYQ_IMAGE=ghcr.io/<owner>/veyq:1.0.0`, a three-part shape this pipeline never emits, and
`infra/docker-compose.prod.yml`'s `${VEYQ_IMAGE:?…}` message repeated the invented shape as `…/veyq:1.2.3`. The
file had been deleted by a "cleanup" commit that also took `Dockerfile` and `scripts/run_web_e2e.py`; those two
were recovered and this one was not, and nothing noticed for the two months in which no container run happened.

Assertion 4 replaced a check that the `Dockerfile` copies the compose file and `.env.example` into the image
(cr-veyqctl, 2026-10-16), and what bought the replacement is the gap that check could not see: the compose file
reads `./garage.toml` beside itself, the image carried only the two files the check listed, and a recipe taken
out of the image could not start its store — `run_stack_e2e.py` had to take `garage.toml` from the checkout
instead (inbox #26, ADR 0096). A listed set is only as complete as the day it was written; this one is derived.

Assertion 5 was bought by a live defect too, and by the cheapest kind — its first run on the untouched tree
named the offender (2026-09-10). The release recipe carried a one-shot init sidecar built from a local
`Dockerfile` into an image no workflow publishes, which existed only because the official Garage image is
`scratch`-based and has no shell for its init script to run in. That was invisible under Compose and fatal
under Helm, and it is what [ADR 0067](../docs/decisions/0067-store-provisioning-from-a-declared-type.md)
removed by moving the bring-up into the backend. It was, measured at that moment, the only `build:` stanza in
either compose file.

Assertion 6 was bought separately and just as concretely (2026-08-17). `src/tools/instance_backup.py` builds
`docker compose -f <file> exec` with **no `-p`**, and no compose file declared a `name:`, so the documented
production capture resolved project `infra`. Measured on a host running the dev stack, that does not fail:
`docker compose -f infra/docker-compose.prod.yml ps` listed all four *dev* containers as its own and the
capture completed, writing a manifest for the wrong instance and reporting success. The rule it was supposed to
obey — *always name the project on the command line* — existed in exactly two places, both prose, enforced by
nothing.

Assertion 7 was bought by the same kind of first run (2026-09-25, cr-image-provenance-verifiable), red three
ways before a line of the fix existed: the only verification command in the repository was a comment in the
publish workflow whose pattern, `^https://github.com/<repo>/`, accepts a signature from any workflow in the
repository on any ref, a pull request's included; `infra/README.md` never told an operator to verify at all;
and the version step still published — and so signed — from a `master` branch nothing else knew about.

Assertions 2 and 3 were reshaped by a defect of the pipeline's own design (cr-release-pipeline-and-chart-oci,
2026-10-14), found when the first push was planned rather than by a run: every push to `main` published the
version in `VERSION`, so every push after the first would have been a red run at the refusal until someone
committed a bump, and there was no way to try the publish path before it named something for good. Publishing
moved behind a dispatch, and the minor into the history. They were reshaped again by ADR 0091 decision 5
(cr-release-promotes-a-tested-snapshot phase 1, 2026-11-06): a snapshot per commit can never be "a release whose name
exists", so every green push publishes one again — and with that, the gate moved from the event to the `version`
job's `kind`, because `scripts/run_pipeline.py` simulates a push and a local run on `main` would otherwise have
reached the registry and pushed a tag to the remote. The tag job's permission line came with the owner's decision of
that day: the job that writes to the repository holds that and nothing else.

Assertion 9 came with folding `container.yml` into `ci.yml` (cr-one-pipeline-per-commit, 2026-10-16), no incident:
the build stopped needing the tests, and from then on the publish's `needs:` is the only thing between a red stage
and a published image — a stage added to the file and forgotten there would be one a release never waited for.
Assertion 10 came with lane L1's job (cr-stack-e2e phase 4), no incident: assertion 9 reads the jobs the file has, so
deleting the lane — or renaming its job and dropping it from the publish's `needs:` in the same edit — was green there.
Its limit is assertion 9's: it reads the file, and proves no run ever waited. Assertion 13 came with the snapshot's
attestations (cr-release-promotes-a-tested-snapshot phase 1), no incident: an attestation that misses an architecture
the lane runs would leave that architecture's result unverifiable at the promotion. Assertion 14 came with the
promotion itself (the same CR, phase 3, 2026-11-06), no incident: it re-homes what assertion 11 held of a release, and
adds what a workflow that holds a release's write permissions must never do — build, write from the wrong job, move a
channel before the release exists, or publish before its files are on it. Assertion 15 came with the review of that
phase (2026-11-06): `release.yml`'s `check` and `promote` and `ci.yml`'s `build` installed PyYAML unpinned from PyPI,
so whatever PyPI served under that name would have run beside the release identity's OIDC token, the drafts and the
registry. Its first run against the untouched `build` job was red, as the bite that restores that line shows.
Assertion 16 came with the first real run of the snapshot (2026-11-06, run 42274582708): every stage green, the snapshot
published and attested, and `snapshot-tag` skipped. Its `if:` named no status function, so GitHub's implicit
`success()` saw the switched-off `upgrade-proof` skipped upstream of `snapshot`, and no `snapshot-` tag was set. The
assertion was written against that form first and was red on it, then the job was fixed.
Assertion 17 came with the owner's decision of 2026-11-07, no incident in the code: every green push published, so
once Dependabot opened its pull requests each merged bump was a snapshot, three images, a chart and a `snapshot-` tag
of its own. Publishing moved to a weekly schedule and the dispatch, gated on HEAD having no snapshot yet; a push still
runs the whole pipeline (ADR 0091 decision 2). Assertions 2 and 3 were reshaped with it: a push's scenarios now
expect `none`.

Assertion 12 came with heavy-stage finding B1 (2026-10-24), which suspected the cache and found the labels instead.
The cache was innocent (above); the labels were not. The app image set no label at all, so `docker inspect` showed its
Ubuntu base's `version` 22.04 and no revision; the admin image showed nginx's `licenses` (Apache-2.0), `source`,
`url` and `created`; and on GitHub the `build` job passed metadata-action's labels, which name the branch (`main`)
as the published image's version and override the Dockerfile's. Nothing reads these labels to decide anything (the
instance reports `VEYQ_VERSION`, `veyqctl` deploys by digest), which is why it stayed unnoticed, and why this is an
assertion here and not a gate somewhere else.

Assertion 11 came with the GitHub Release (cr-veyqctl phase 5), and building it found a defect before any publish:
the template was carried as `.env.example`, and GitHub renames a release asset whose name starts with a period (its
REST docs, "Upload a release asset"), so every `veyqctl install` from GitHub would have fetched a name the release does
not have. The producer now names it `env.example` and refuses a recipe file GitHub would rename, and assertion 4 holds
`veyqctl`'s asset names to what the producer writes. Its two signatures came with inbox #469 (cr-signed-release-manifest):
the manifest was unsigned, so whoever controls the source could serve a forged manifest with a forged compose file that
matched it. Its release half left this pipeline with the release kind (ADR 0091 decision 5) and is assertion 14 now.

Assertion 11's cosign half came with inbox #474, from a measured gap rather than an incident: the publish installed
cosign through `sigstore/cosign-installer` v3.9.1, whose default is cosign v2.5.2, while `veyqctl` and the Run page
refuse anything older than 3.1.3 — so the one cosign that had never read a veyq bundle was the one writing them. The
v4.1.2 installer's own default is v3.0.6, inside the advisory's range, which is why the version is pinned and held
here. And the bundle flag this assertion used to REQUIRE had become the hazard: cosign 3.1.3 marks it deprecated
("this will be the only supported format in future versions", its `--help` hides it), so the requirement would have
broken the publish at the first cosign that removes it; its `=false` spelling, measured, writes the legacy bundle.

Assertion 8 came with the chart's OCI release (same CR): no incident, the chart had never been published. Each of
its halves was bitten by injection before it counted, and the measurement that shaped it is in ADR 0064 — `helm
push` appends the chart's name to the target, which is why the target ends in `/charts`.

**A note for whoever edits this next, because the first run of assertion 5 tripped on it:** this guard scans
durable state for a literal, and its own file is durable state, so spelling that literal here makes the guard
flag itself. Describe the flag, do not write it. The repair that suggests itself — excluding this file from the
scan — puts a hole in exactly the place a future instruction would hide.

**Its limit — what a green run does NOT prove.** It reads **files**, and executes exactly one of them (the
version step's shell, against a history it built — so `fetch-depth: 0` on the real checkout is proven only by a
run, where its absence is a loud red at the shallow refusal — and so is the tag check's premise, that the same
checkout holds every tag). Assertion 17 evaluates one expression, the concurrency group, by a model of GitHub's
expression language that knows `format`, `==`, `!=`, `&&`, `||`, `!`, string literals, `github.event_name` and
`github.ref` and refuses anything else (red, never a guess); that GitHub cancels and queues by that group as its docs
say is a run's to show, and the schedule firing at all is GitHub's (it runs on the default branch only, and a public
repository's schedule is switched off after 60 days without activity). It cannot prove a snapshot was ever published, that a
tag was created, that a second build of a commit was refused, or that the registry holds what the workflow says it
would push — those are a run's on GitHub (the remote exists since 2026-11-05; the first-run list is inbox #471), and
the publish path stays an explicitly stated ADR 0040 gap. It says nothing
about the backup manifest's `app.version` (its observable is a capture run against a live instance, not a file),
and its scan for written-down image tags covers `TAG_SCAN_DIRS` and the Run pages only: `docs/` quotes the *old, wrong*
shapes on purpose, as the defect being described.

Assertion 4's limits. **The guard and the producer share one derivation**, so a way of reading a file that the
derivation does not know — a Compose key added after it was written — is missed by both, and green; the spellings
it knows are the list above. **An absolute host path is the host's, unchecked**: `/srv/…` or `~/…` in the compose
file is neither carried nor refused. It runs the carry step, never the whole producer (the release number needs the
full history, and this guard's checkout may be shallow), and never a published release: that a GitHub Release holds
these assets is a publish's to prove.

Assertion 11's limits (its cosign half). It reads the installer's `cosign-release` input, not the binary a run puts on
the path: that the installer installs exactly that version is the action's (it verifies the download with a bootstrap
cosign it pins by sha256), and a `cosign` fetched another way — a `curl`, another action — is not recognised. A
`cosign` called through a variable or a script is not a line here.

Assertion 13's limits. It reads the workflow, not the registry: that `cosign attest` stored the predicate where a
promotion finds it is a run's to show. The predicates' own refusals (a missing, extra, failed or inconsistent leg) are
`scripts/snapshot_selftest.py`'s, which `guards` runs; this assertion holds the arguments the workflow hands them.

Assertion 14's limits. It reads the file's lines and job graph, not GitHub: that a draft can be moved onto another tag,
that `gh release upload` finds a draft by its tag, that `make_latest` and the immutable-releases setting behave as the
REST docs say, and that Buildx copies a single index unchanged are the first promotion's to show (inbox #471); the
workflow reads back what it can (the moved draft's tag, the attached files, every new name's digest) and fails where
one differs, before publication. A write it does not recognise (another CLI, a `curl` to the API) is not held, and
the job table is by name: a job renamed is red until this guard learns its permissions.

Assertion 3's gating is read as a conservative model of GitHub's expression language: an `if:` counts as gated
only if it is a pure `&&` chain with the publishing test as one term, so an equivalent condition
spelled any other way is a false red, never a false green. A registry write it does not recognise — a new tool,
or an action other than the login and build-push ones — is not held; the recognised set is the regex and the two
action names in the code. The write-once refusal's decisions are `scripts/snapshot.py`'s (its self-test); this
assertion holds that the step runs it, on the tree's version, before the job's first write.

**Assertion 5's own limit, and it is deliberate: it is scoped to the RELEASE RECIPE and says nothing about
`infra/docker/docker-compose.yml`.** A chart deploys the recipe, not the dev stack, and a dev-only `build:` is a
legitimate thing to want — so forbidding one from a guard whose subject is *releases* would be this guard
overreaching into a file it has no argument about. It also reads the compose file rather than a registry, so
"the workflow publishes this name" means the matrix says so, never that a push ever succeeded (see the
no-remote limit above).

Assertion 6 adds two limits of its own, and the first is the one that matters. **It reads the file, so it says
nothing about the project the operator actually brought their stack up under.** `docker compose -p something-else
-f infra/docker/docker-compose.prod.yml up -d` overrides the declared name, and so does `COMPOSE_PROJECT_NAME` — a
green here means the *procedure* can no longer be wrong by default, never that a given host is right. `veyqctl`
closes that for an instance it manages — it records the project at `install` and names it on every command — which
is the tool's job and explicitly not this guard's.
Second, the flag scan covers **durable state only**: `changes/` and `docs/inbox.md` are the flow surfaces
(`docs/ai/README.md` § State vs. flow) and necessarily go on naming a retired rule in order to record that it
was retired, so an instruction hidden in a work order is out of reach. The line drawn is the repository's own,
not a convenience — a whitelist of files that happened to be red would have been the convenience.

Assertion 7's limits. **It proves the documented command's shape, never that it runs**: no veyq release has been
published, so it has never verified one (it was run once by hand, as written, against another image keyless-
signed from a GitHub workflow on `main`, with that image's identity). It judges the identity pattern with Python's
`re.search` as a model of cosign's Go `regexp.MatchString` — unanchored, which is why a pattern without `^…$`
fails here as it would there. The two engines agree on what the pattern uses (anchors, classes, alternation); a
construct where they diverge would be judged by the wrong one. The rejected identities are a probe list of the
ways a pattern is too wide, not a proof that no other string is accepted; the accepted ones are derived. The
registry (`ghcr.io`) is a constant here. And it cannot see whether an operator runs the command at all. For
`veyqctl` it reads the two constants, not the `cosign verify` and `cosign verify-blob` calls that pass them:
`src/tools/veyqctl_selftest.py` holds both calls to exactly those two values.
"""
from __future__ import annotations

import ast
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the other guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

import release_assets  # the producer: assertion 4 holds it to its own derivation, assertion 2 its release number
import repo_layout  # where the deployment files and the test harnesses live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent

VERSION_FILE = ROOT / "VERSION"
WORKFLOW = ROOT / ".github/workflows/ci.yml"
# The tracked files whose written-down image tags assertion 4 scans: the deployment, and the two harnesses that run
# the deployment (lane L1's overlay, the API fuzzer) - named, so a move of either does not quietly change the scan.
TAG_SCAN_DIRS = (repo_layout.INFRA_DIR, repo_layout.E2E_STACK_DIR, repo_layout.FUZZ_DIR)

# `VERSION` holds the major alone; the minor is a commit count, so no tree can hold it (ADR 0057 §1).
MAJOR = re.compile(r"^([0-9]+)$")
# `<major>.<minor>` — a release's whole identity. Not three parts: the pipeline emits `<major>`, `<major>.<minor>`,
# `<major>.<minor>-SNAPSHOT`, `sha-<sha>` and `latest`, and a patch component would name an artifact with no producer.
RELEASE_VERSION = re.compile(r"^([0-9]+)\.([0-9]+)$")

# Step ids this guard anchors on. A rename makes it say "step not found" rather than pass vacuously.
VERSION_JOB, VERSION_STEP = "version", "veyq-version"
# The job that publishes a snapshot, and the one job that writes to the repository: its git tag (ADR 0091 decision 5).
PUBLISH_JOB = "snapshot"
SNAPSHOT_TAG_JOB = "snapshot-tag"
REFUSE_STEP, TAG_STEP = "assert-snapshot-unpublished", "snapshot-tag"
BUILD_JOB = "build"
# Lane L1's orchestrator: assertion 10 finds the lane's job by running it, never by the job's name.
LANE_ORCHESTRATOR = "scripts/run_stack_e2e.py"
# The snapshot's decisions (assertion 13): a lane leg's record, the predicates, the write-once refusal.
SNAPSHOT_SCRIPT = "scripts/snapshot.py"
CHART_DIR = repo_layout.CHART_DIR
# The operator's verification is a page of the docs site's Run area (ADR 0087), kept `.md` with its commands in
# plain fenced blocks so this guard can read them; it moved there from infra/README.md, which keeps the dev stack.
VERIFY_PAGE = ROOT / "site/src/content/docs/run/verify-a-release.md"
# The workflow whose keyless signature says "release": the promotion of a snapshot (ADR 0091 decision 5; assertion 14
# holds what it does). Named once, here; the identity's owner and repository are
# not — they are the chart's `home:` (assertion 7), so a move of the repository changes that one line and this guard
# names every line that must follow it (`veyqctl`'s constant, the Run page's commands, the chart's image defaults).
RELEASE_WORKFLOW = ".github/workflows/release.yml"

# The tree-derived version, as the workflow refers to it. Every release-naming step must key on THIS and not on
# a registry query, a `git describe`, or a hand-typed literal.
VERSION_EXPR = "${{ needs.version.outputs.version }}"

# A veyq image reference with a literal tag: `…/veyq:<tag>` or a bare `veyq:<tag>`. The character after `veyq`
# must be `:`, so a `veyq-<something>:<tag>` sibling is not claimed as the app image — live today for
# `veyq-admin:<tag>`, which the release recipe names beside the app and which is a DIFFERENT published image
# (the same discrimination validate_config_secret_persistence.py's APP_IMAGE makes, and for the same reason).
IMAGE_TAG = re.compile(r"(?:^|[\s/=\"'`])veyq:([^\s\"'`,;)}\]]+)")

# Anything that would make the version a function of the network rather than of the tree — a `git` that talks to
# a remote included, because deepening a shallow clone is the obvious wrong repair for the shallow refusal.
NETWORK_TOOLS = re.compile(r"(?:^|[\s|(&;])(gh|curl|wget|nc|git\s+(?:fetch|pull|clone|ls-remote))\s", re.MULTILINE)

# The step's inputs, each from the expression a run actually hands it. The scenarios below set exactly these, so a
# workflow that stopped passing one would be executed here with a value it never receives.
STEP_ENV = {
    "EVENT_NAME": "${{ github.event_name }}",
    "REF_NAME": "${{ github.ref_name }}",
    "RUNNER_ENVIRONMENT": "${{ runner.environment }}",
}

# What every write of the pipeline is gated on: the `version` job's verdict that this run publishes a snapshot (a push
# or a dispatch on a publishing branch, on GitHub's runners) — never a pull request, never a local run (ADR 0091 decision 5).
PUBLISHES = "needs.version.outputs.kind == 'snapshot'"
# A `run:` that writes to a registry or to the remote — a GitHub Release included. `uses:` writers are recognised by
# action below. `cosign sign` matches `cosign sign-blob` too, and `cosign attest` is a write as well: both write to the
# transparency log, and an attestation to the registry.
REGISTRY_WRITE_RUN = re.compile(
    r"\b(?:helm\s+push|helm\s+registry\s+login|cosign\s+sign|cosign\s+attest|imagetools\s+create|docker\s+push|docker\s+login"
    r"|git\s+push|oras\s+push|gh\s+release\s+(?:create|upload|edit|delete|delete-asset))\b"
    r"|\bgh\s+api\b[^\n]*\bgit/refs\b"
)
# A write to the repository's refs: a `git push`, or a ref created or moved through the REST API (`gh api … git/refs`),
# which is how the tag job writes — a GITHUB_TOKEN `git push` of a tag is refused once main moved a workflow file.
GIT_PUSH = re.compile(r"\bgit\s+push\b|\bgh\s+api\b[^\n]*\bgit/refs\b")
# A GitHub Release, or anything else that writes to one: a snapshot gets none (ADR 0096 decision 3), and a release is a
# promotion in a workflow of its own.
GH_RELEASE_CREATE = re.compile(r"\bgh\s+release\s+create\b")
RELEASE_ASSET_WRITE = re.compile(
    r"\bgh\s+release\s+(?:upload|edit|delete|delete-asset)\b|uploads\.github\.com|\bgh\s+api\b[^\n]*/releases"
)
RELEASE_ACTION = re.compile(r"release", re.IGNORECASE)  # an action's `owner/name`: softprops/action-gh-release, …
# Deprecated in cosign 3 (default true, `--help` hides it); only `=false` still changes anything — to the legacy format.
NEW_BUNDLE_FORMAT = "--new-bundle-format"
COSIGN_INSTALLER = "sigstore/cosign-installer"
COSIGN_RUN = re.compile(r"(?:^|[\s;&|(])cosign\s")
EXACT_VERSION = re.compile(r"^v([0-9]+)\.([0-9]+)\.([0-9]+)$")
FIRST_PUBLISH_WRITE = re.compile(r"\b(?:imagetools\s+create|cosign\s+sign|cosign\s+attest|helm\s+push|git\s+push|gh\s+release)\b"
                                 r"|\bgh\s+api\b[^\n]*\bgit/refs\b")
COSIGN_ATTEST = re.compile(r"\bcosign\s+attest\b")


def non_release_branches(major: str) -> list[str]:
    """Adversarial branch names: each must be refused by the version step AND by the documented verify.

    A probe list, not an anchor set — the branches that DO publish are derived from the scenarios' outcomes.
    """
    return ["master", "develop", "main-next", f"release/{major}.x-rc", f"release/{major}.x/hotfix"]


# Filled by assertion 2's scenarios: every branch a run on which the version step turns into a publish.
publishing_branches: list[str] = []
# Filled by the same scenarios, for assertion 17: (event, checkout, label) of every one that came out `kind=snapshot`.
publishing_runs: list[tuple[str, str, str]] = []
# The publishing events those scenarios ran on a HEAD that already has its snapshot (and the step did not refuse).
tagged_probes: list[str] = []
# The events that may publish (assertion 17, owner 2026-11-07): the weekly schedule and the owner's dispatch.
PUBLISHING_EVENTS = {"schedule", "workflow_dispatch"}

errors: list[str] = []
signing_cosign = ""  # assertion 11's installer pin, for the OK line


def fail(message: str) -> None:
    errors.append(message)


# ---- 1. the release identity itself ------------------------------------------------------------------------

version_text = VERSION_FILE.read_text() if VERSION_FILE.is_file() else None
version_value = version_text.strip() if version_text is not None else None
version_match = MAJOR.match(version_value) if version_value else None

if version_text is None:
    fail(
        "there is no VERSION file at the repository root. It IS the release identity (ADR 0057 §1): without it "
        "the publish workflow dies on its first command and nothing can ever be published, while "
        "infra/docker/.env.example tells the operator to pin an image tag that has no producer."
    )
elif not version_match:
    fail(
        f"VERSION holds {version_value!r}, which is not the major alone. The minor is the number of commits since "
        f"VERSION last changed (ADR 0057 §1), so a tree cannot hold it — a minor written here is one the "
        f"publish would ignore or contradict."
    )
elif version_text != f"{version_value}\n":
    fail(
        f"VERSION must be exactly `{version_value}` and one trailing newline — it is read by shell, by this "
        f"guard and by a human, and whitespace that only one of them tolerates is a difference of opinion."
    )


# ---- 2. the workflow derives that value, offline -------------------------------------------------------------

def job_steps(doc: dict, job: str) -> list[dict]:
    steps = ((doc.get("jobs") or {}).get(job) or {}).get("steps") or []
    return [s for s in steps if isinstance(s, dict)]


def veyqctl_constants(pattern: str) -> dict[str, str]:
    """`src/tools/veyqctl.py`'s module-level string constants whose names match `pattern`, read without importing it."""
    path = repo_layout.VEYQCTL
    if not path.is_file():
        return {}
    found = {}
    for node in ast.parse(path.read_text()).body:
        if (isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                and re.fullmatch(pattern, node.targets[0].id)
                and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str)):
            found[node.targets[0].id] = node.value.value
    return found


def step_by_id(doc: dict, job: str, step_id: str) -> dict | None:
    return next((s for s in job_steps(doc, job) if s.get("id") == step_id), None)


def flag_values(argv: list[str], name: str) -> list[str]:
    values = []
    for index, token in enumerate(argv):
        if token == name and index + 1 < len(argv):
            values.append(argv[index + 1])
        elif token.startswith(name + "="):
            values.append(token.split("=", 1)[1])
    return values


def git(cwd: pathlib.Path, *args: str) -> str:
    """git with no user or system config, so a signing hook or alias on this machine cannot shape the history."""
    proc = subprocess.run(
        ["git", "-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", *args],
        cwd=cwd,
        env={
            "PATH": "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
            "HOME": str(cwd),
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_AUTHOR_NAME": "release-identity",
            "GIT_AUTHOR_EMAIL": "guard@invalid",
            "GIT_COMMITTER_NAME": "release-identity",
            "GIT_COMMITTER_EMAIL": "guard@invalid",
        },
        capture_output=True,
        text=True,
        timeout=30,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {proc.stderr.strip()}")
    return proc.stdout


# The throwaway history the version step is executed against: a known count, never this repository's own
# history, which moves with every commit. VERSION changes TWICE, so a step that counted from the first change
# (5) or counted every commit (6) is told apart from one that counts from the last change (3).
EXPECTED_MINOR = 3


def build_history(tmp: pathlib.Path, major: str) -> dict[str, pathlib.Path]:
    """Clones of a history whose VERSION last changed EXPECTED_MINOR commits ago, keyed by what HEAD carries.

    `full`: the commit before HEAD carries its `snapshot-` tag, HEAD none (assertion 17's decoy: an earlier week's
    snapshot must not stop this one). `tagged`: the same history, HEAD carrying its own `snapshot-` tag (it is
    published). `shallow`: one commit deep, which the step must refuse.
    """
    origin = tmp / "origin"
    origin.mkdir()
    git(origin, "init", "-q", "-b", "main")

    def commit(message: str, **files: str) -> None:
        for name, body in files.items():
            (origin / name).write_text(body)
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", message)

    commit("an older major", VERSION=f"{int(major) + 41}\n", notes="1\n")
    commit("work under it", notes="2\n")
    commit("the major this tree holds", VERSION=version_text or "")
    for n in range(EXPECTED_MINOR):
        commit(f"work {n}", notes=f"{n + 3}\n")
    # What the pipeline itself would have left on the parent: its snapshot's lightweight tag (`snapshot-tag`'s shape).
    git(origin, "tag", f"snapshot-{major}.{EXPECTED_MINOR - 1}", "HEAD~1")
    full = tmp / "full"
    tagged = tmp / "tagged"
    shallow = tmp / "shallow"
    git(tmp, "clone", "-q", "--no-local", str(origin), str(full))
    git(tmp, "clone", "-q", "--no-local", str(origin), str(tagged))
    git(tagged, "tag", f"snapshot-{major}.{EXPECTED_MINOR}", "HEAD")
    git(tmp, "clone", "-q", "--depth", "1", f"file://{origin}", str(shallow))
    return {"full": full, "tagged": tagged, "shallow": shallow}


def run_scenario(script: str, checkout: pathlib.Path, env: dict[str, str]) -> tuple[int, dict[str, str], str]:
    """Execute the workflow's own version script offline and read back what it wrote to $GITHUB_OUTPUT."""
    out = checkout.parent / f"github_output_{checkout.name}"
    out.write_text("")
    proc = subprocess.run(
        ["bash", "-c", script],
        cwd=checkout,
        env={"PATH": "/usr/bin:/bin", "HOME": str(checkout), "GITHUB_OUTPUT": str(out), **env},
        capture_output=True,
        text=True,
        timeout=30,
    )
    written = {}
    for line in out.read_text().splitlines():
        key, _, value = line.partition("=")
        written[key] = value
    return proc.returncode, written, (proc.stderr or proc.stdout).strip()


workflow_doc: dict = {}
if not WORKFLOW.is_file():
    fail(f"{WORKFLOW.relative_to(ROOT)} is missing — there is no publish pipeline to hold to the tree")
else:
    try:
        workflow_doc = yaml.safe_load(WORKFLOW.read_text()) or {}
    except yaml.YAMLError as e:
        fail(f"{WORKFLOW.relative_to(ROOT)} is not readable YAML: {e}")

compute = step_by_id(workflow_doc, VERSION_JOB, VERSION_STEP) if workflow_doc else None
if workflow_doc and compute is None:
    fail(
        f"{WORKFLOW.relative_to(ROOT)}: no step `id: {VERSION_STEP}` in job `{VERSION_JOB}` — this guard "
        f"executes that step to assert what a publish would stamp, and cannot find it"
    )
elif compute is not None and version_match:
    script = str(compute.get("run") or "")
    step_env = {str(k): str(v) for k, v in (compute.get("env") or {}).items()}
    for key, expression in STEP_ENV.items():
        if step_env.get(key) != expression:
            fail(
                f"the `{VERSION_STEP}` step's env sets `{key}` to `{step_env.get(key)}`, not `{expression}` — this "
                f"guard executes the step with `{key}` set the way a run sets it, so a step that no longer receives "
                f"it would be proven here on an input it never gets"
            )
    if "${{" in script:
        fail(
            f"the `{VERSION_STEP}` step's script interpolates a `${{{{ }}}}` expression, so it cannot be run "
            f"outside GitHub Actions and this guard cannot assert what it would stamp. Pass values through "
            f"the step's `env:` instead, which is what makes the derivation locally checkable."
        )
    elif (tool := NETWORK_TOOLS.search(script)) is not None:
        fail(
            f"the `{VERSION_STEP}` step calls `{tool.group(1)}` — the version must be a function of the tree, "
            f"not of the network. A registry query makes the released artifact the authority on its own name, "
            f"cannot be checked without a network, and its error path republishes silently (ADR 0057 §1)."
        )
    else:
        major = version_match.group(1)
        release = f"{major}.{EXPECTED_MINOR}"

        def event(name: str, branch: str, runner: str = "github-hosted") -> dict[str, str]:
            return {"EVENT_NAME": name, "REF_NAME": branch, "RUNNER_ENVIRONMENT": runner}

        # The stamp is `<major>.<minor>` on EVERY event (ADR 0091 decision 5: a release is the snapshot's bytes, so the
        # two carry one name); only `kind` says whether the run publishes.
        stamp = {"version": release, "major": major}
        # (label, checkout, env, expected exit, expected outputs). The checkout is build_history's key: `full` (HEAD has
        # no snapshot yet, its parent has), `tagged` (HEAD has its snapshot), `shallow`.
        scenarios = [
            (
                # Owner 2026-11-07: a push runs the whole pipeline and publishes nothing (assertion 17).
                "a push to main",
                "full", event("push", "main"), 0, {"kind": "none", **stamp},
            ),
            (
                f"a push to release/{major}.x",
                "full", event("push", f"release/{major}.x"), 0, {"kind": "none", **stamp},
            ),
            (
                # GitHub runs a schedule on the default branch only, so `main` is the one branch it reaches.
                "the weekly schedule on main",
                "full", event("schedule", "main"), 0, {"kind": "snapshot", **stamp},
            ),
            (
                "a dispatch on main",
                "full", event("workflow_dispatch", "main"), 0, {"kind": "snapshot", **stamp},
            ),
            (
                f"a dispatch on release/{major}.x",
                "full", event("workflow_dispatch", f"release/{major}.x"), 0, {"kind": "snapshot", **stamp},
            ),
            (
                # A snapshot is written once and never replaced: a HEAD that has one is tested again, not published.
                "the weekly schedule on main, whose HEAD already has its snapshot",
                "tagged", event("schedule", "main"), 0, {"kind": "none", **stamp},
            ),
            (
                "a dispatch on main, whose HEAD already has its snapshot",
                "tagged", event("workflow_dispatch", "main"), 0, {"kind": "none", **stamp},
            ),
            (
                f"a dispatch on release/{major}.x, whose HEAD already has its snapshot",
                "tagged", event("workflow_dispatch", f"release/{major}.x"), 0, {"kind": "none", **stamp},
            ),
            (
                # Dependabot's included: a pull request builds and tests, and publishes nothing.
                "a pull request",
                "full", event("pull_request", "12/merge"), 0, {"kind": "none", **stamp},
            ),
            (
                # scripts/run_pipeline.py simulates a push on `runner.environment` `local`: a local run on main must
                # not reach a registry, a signature or the remote.
                "a local run of a push to main",
                "full", event("push", "main", runner="local"), 0, {"kind": "none", **stamp},
            ),
            (
                # ... nor turn red on a feature branch, which is where a local run usually is.
                "a local run of a push to a feature branch",
                "full", event("push", "feature/x", runner="local"), 0, {"kind": "none", **stamp},
            ),
            (
                # A shallow clone counts 1: the step must refuse it rather than stamp a wrong minor that looks right.
                "a push to main from a shallow checkout",
                "shallow", event("push", "main"), 1, {},
            ),
            (
                "the weekly schedule on main from a shallow checkout",
                "shallow", event("schedule", "main"), 1, {},
            ),
            (
                "a pull request from a shallow checkout",
                "shallow", event("pull_request", "12/merge"), 1, {},
            ),
        ] + [
            # A ref that disagrees with the tree, and branches that are not release branches, must publish nothing:
            # whatever publishes also signs, and assertion 7 holds the documented verify to rejecting exactly these
            # (ADR 0057 §3: what signs and what verifies agree). `master` is the one a publish accepted until
            # cr-image-provenance. A push, a schedule or a dispatch on such a branch is red, not a green run that
            # looks like a publishing one.
            (
                f"a {name} on {branch}, which is not a release branch of this tree",
                "full", event(name, branch), 1, {},
            )
            for branch in [f"release/{int(major) + 7}.x", *non_release_branches(major)]
            for name in ("push", "schedule", "workflow_dispatch")
        ]
        try:
            with tempfile.TemporaryDirectory(prefix="release_identity_") as tmp:
                checkouts = build_history(pathlib.Path(tmp), major)
                full, shallow = checkouts["full"], checkouts["shallow"]
                # The producer counts the release a second time, in Python (it names the release's assets); the
                # two counts must agree, and its copy must refuse a shallow checkout as the step does.
                try:
                    produced = release_assets.tree_release(full)
                except release_assets.ReleaseError as e:
                    produced = f"a refusal ({e})"
                if produced != release:
                    fail(
                        f"scripts/release_assets.py counts this history as `{produced}` where the `{VERSION_STEP}` "
                        f"step counts `{release}` — a release's assets would carry another number than its images"
                    )
                try:
                    release_assets.tree_release(shallow)
                    fail("scripts/release_assets.py counts a release from a shallow checkout, which the version step refuses")
                except release_assets.ReleaseError:
                    pass
                for label, checkout, env, expect_rc, expect in scenarios:
                    try:
                        rc, written, message = run_scenario(script, checkouts[checkout], env)
                    except subprocess.TimeoutExpired:
                        fail(f"the `{VERSION_STEP}` step hung for {label} — a version derivation that waits is waiting on a network")
                        continue
                    if rc == 0 and checkout == "tagged" and env["EVENT_NAME"] in PUBLISHING_EVENTS:
                        tagged_probes.append(env["EVENT_NAME"])
                    if rc == 0 and written.get("kind") == "snapshot":
                        publishing_branches.append(env["REF_NAME"])
                        publishing_runs.append((env["EVENT_NAME"], checkout, label))
                    if expect_rc == 0 and rc != 0:
                        fail(f"the `{VERSION_STEP}` step fails for {label}: {message or f'exit {rc}'}")
                        continue
                    if expect_rc != 0:
                        if rc == 0:
                            fail(
                                f"the `{VERSION_STEP}` step accepts {label} and would stamp "
                                f"`{written.get('version')}` (kind `{written.get('kind')}`) — a snapshot names "
                                f"itself from the full history and is published only from main or "
                                f"release/<major>.x, so a ref that disagrees with VERSION, any other branch, and a "
                                f"shallow checkout must fail rather than publish a signed artifact under a wrong name"
                            )
                        continue
                    for key, want in expect.items():
                        if written.get(key) != want:
                            fail(
                                f"for {label} the `{VERSION_STEP}` step wrote `{key}={written.get(key)}`, expected "
                                f"`{key}={want}` (VERSION says {version_value}, and it last changed "
                                f"{EXPECTED_MINOR} commits before HEAD)"
                            )
        except (RuntimeError, subprocess.TimeoutExpired) as e:
            fail(f"could not build the throwaway history the version step is executed against, so it checked NOTHING: {e}")
        publishing_branches[:] = sorted(set(publishing_branches))

# The value the workflow derives must be the value stamped into the image, or the instance reports a version
# that is not the one it was published under.
build_args = "\n".join(
    str((s.get("with") or {}).get("build-args") or "") for s in job_steps(workflow_doc, BUILD_JOB)
)
if workflow_doc and f"VEYQ_VERSION={VERSION_EXPR}" not in build_args:
    fail(
        f"the `{BUILD_JOB}` job does not pass `VEYQ_VERSION={VERSION_EXPR}` as a build-arg — the running "
        f"instance would then report a release version that is not the one it was published under"
    )


# The stamp lands only in the stage a publish ships. A build stage that declares it compiles it into bits the
# image serves — the web bundle is served before any login, so that is the release on the pre-auth surface
# (ADR 0057 §4); the obvious repair for an export manifest that says `dev` is exactly that. And a stamp the target
# stage does not declare is silently dropped by Docker, so the instance reports no release at all.
ARG_LINE = re.compile(r"^\s*ARG\s+(.+?)\s*$", re.IGNORECASE)
FROM_LINE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*(?:\s+AS\s+(\S+))?\s*$", re.IGNORECASE)


def stage_args(body: str) -> list[tuple[str | None, set[str]]]:
    """Each stage's declared ARG names, in order; the stage name is None for the preamble and an unnamed stage."""
    stages: list[tuple[str | None, set[str]]] = [(None, set())]
    for line in body.splitlines():
        start = FROM_LINE.match(line)
        if start:
            stages.append((start.group(1), set()))
            continue
        arg = ARG_LINE.match(line)
        if arg:
            stages[-1][1].update(token.split("=", 1)[0] for token in arg.group(1).split())
    return stages


STEP_FOR_IMAGE = re.compile(r"^\s*matrix\.image\s*(==|!=)\s*'([^']+)'\s*$")


def stamps_for(image: str) -> set[str]:
    """The build-args the build steps stamp into one matrix entry: a step whose `if:` is `matrix.image == '<x>'` builds
    that image alone, one whose `if:` is `matrix.image != '<x>'` every other, and any other step every image — so a
    stamp only one image declares (the tools image's major entry) is held to that image's target, and to no other."""
    found: set[str] = set()
    for step in job_steps(workflow_doc, BUILD_JOB):
        args = str((step.get("with") or {}).get("build-args") or "")
        if not args:
            continue
        gate = STEP_FOR_IMAGE.match(str(step.get("if") or ""))
        if gate and (gate.group(1) == "==") != (gate.group(2) == image):
            continue
        found |= {line.split("=", 1)[0].strip() for line in args.splitlines() if "=" in line}
    return found


stamped = {line.split("=", 1)[0].strip() for line in build_args.splitlines() if "=" in line}
stamped_targets = 0
if workflow_doc:
    matrix = ((workflow_doc.get("jobs") or {}).get(BUILD_JOB) or {}).get("strategy", {}).get("matrix", {}) or {}
    entries = [e for e in (matrix.get("include") or []) if isinstance(e, dict) and "dockerfile" in e]
    if not stamped:
        fail(f"the `{BUILD_JOB}` job passes no build-args — there is no stamp to hold to its stage, so this checked NOTHING")
    if not entries:
        fail(
            f"the `{BUILD_JOB}` job's matrix names no `dockerfile` — the stage a stamp belongs in cannot be found, "
            f"so this checked NOTHING"
        )
    for entry in entries:
        dockerfile, target = ROOT / str(entry["dockerfile"]), str(entry.get("target") or "")
        where = f"{dockerfile.relative_to(ROOT)}"
        if not dockerfile.is_file():
            fail(f"the `{BUILD_JOB}` matrix builds `{where}`, which does not exist")
            continue
        stages = stage_args(dockerfile.read_text())
        targets = [args for name, args in stages if name == target]
        if len(targets) != 1:
            fail(f"`{where}` has {len(targets)} stages named `{target}`, the target the `{BUILD_JOB}` matrix publishes")
            continue
        stamped_targets += 1
        mine = stamps_for(str(entry.get("image") or ""))
        if "VEYQ_VERSION" not in mine:
            fail(f"no build step of the `{BUILD_JOB}` job stamps `VEYQ_VERSION` into `{entry.get('image')}` — that "
                 f"image would report no release")
        for name in sorted(mine - targets[0]):
            fail(
                f"`{where}`'s `{target}` stage does not declare `ARG {name}`, which the `{BUILD_JOB}` job passes — "
                f"Docker drops an undeclared build-arg without failing, so the published image carries no {name}"
            )
        for name, args in stages:
            if name == target:
                continue
            for leaked in sorted(mine & args):
                fail(
                    f"`{where}` declares `ARG {leaked}` in "
                    + (f"its `{name}` stage" if name else "a stage with no name or before its first FROM")
                    + f", not only in `{target}`, the stage a publish ships. A build stage that reads the stamp "
                    f"compiles it into what the image serves, and the web client is served before any login — the "
                    f"precise release never goes there (ADR 0057 §4). An export names the major instead."
                )


# ---- 12. the image's labels name the build that made it --------------------------------------------------------

LABEL_START = re.compile(r"^LABEL\s+(.*)$", re.IGNORECASE | re.DOTALL)
# The two identity labels and the stamp each must come from.
IDENTITY_LABELS = {"org.opencontainers.image.version": "VEYQ_VERSION", "org.opencontainers.image.revision": "VEYQ_REVISION"}


def instructions(body: str) -> list[str]:
    """The Dockerfile's instructions, one per entry: continuation lines joined, comment lines dropped (Docker drops a
    comment line inside a continued instruction too)."""
    out: list[str] = []
    pending = ""
    for line in body.splitlines():
        if line.strip().startswith("#") or (not line.strip() and not pending):
            continue
        if line.rstrip().endswith("\\"):
            pending += line.rstrip()[:-1] + " "
            continue
        out.append((pending + line).strip())
        pending = ""
    if pending:
        out.append(pending.strip())
    return out


def stage_labels(body: str) -> dict[str | None, dict[str, tuple[str, frozenset[str]]]]:
    """Per stage name: each label key the stage sets, with its raw value and the ARG names the stage had declared by
    then (a stamp read before its stage declares it expands to nothing)."""
    stages: dict[str | None, dict[str, tuple[str, frozenset[str]]]] = {None: {}}
    current: str | None = None
    declared: set[str] = set()
    for instruction in instructions(body):
        start = FROM_LINE.match(instruction)
        if start:
            current, declared = start.group(1), set()
            stages[current] = {}
            continue
        arg = ARG_LINE.match(instruction)
        if arg:
            declared.update(token.split("=", 1)[0] for token in arg.group(1).split())
            continue
        label = LABEL_START.match(instruction)
        if label:
            for pair in shlex.split(label.group(1)):
                key, _, value = pair.partition("=")
                stages[current][key] = (value, frozenset(declared))
    return stages


labelled_targets = 0
if workflow_doc:
    for entry in entries:
        dockerfile, target, image = ROOT / str(entry["dockerfile"]), str(entry.get("target") or ""), str(entry.get("image") or "")
        if not dockerfile.is_file():
            continue  # reported above
        where = f"{dockerfile.relative_to(ROOT)}"
        labels = stage_labels(dockerfile.read_text()).get(target)
        if labels is None:
            continue  # reported above
        labelled_targets += 1
        title = labels.get("org.opencontainers.image.title", ("", frozenset()))[0]
        if title != image:
            fail(f"`{where}`'s `{target}` stage labels its title `{title or '(none)'}`, not `{image}`, the image the "
                 f"`{BUILD_JOB}` matrix publishes from it")
        mine = stamps_for(image)
        for key, stamp in IDENTITY_LABELS.items():
            if stamp not in mine:
                fail(f"no build step of the `{BUILD_JOB}` job stamps `{stamp}` into `{image}` — its `{key}` label "
                     f"would name no build")
            value, declared = labels.get(key, (None, frozenset()))
            if value not in (f"${{{stamp}}}", f"${stamp}"):
                fail(
                    f"`{where}`'s `{target}` stage sets `{key}` to `{value if value is not None else '(nothing)'}`, "
                    f"not `${{{stamp}}}` — `docker inspect` would then name a build this image is not (a base image "
                    f"lends its own: Ubuntu's `version` is 22.04)"
                )
            elif stamp not in declared:
                fail(f"`{where}`'s `{target}` stage sets `{key}` before it declares `ARG {stamp}`, so the label "
                     f"expands to nothing")
    # A label passed to the build overrides the Dockerfile's: the identity has one place, the target stage.
    for job_name, job in (workflow_doc.get("jobs") or {}).items():
        for step in job_steps(workflow_doc, job_name):
            name = step.get("name") or step.get("id") or "?"
            if "docker/build-push-action" in str(step.get("uses") or "") and (step.get("with") or {}).get("labels"):
                fail(f"the `{job_name}` job's step `{name}` passes `labels:` to its build — a passed label overrides "
                     f"the target stage's, and metadata-action's name the branch as the image's version")
            for line in str(step.get("run") or "").replace("\\\n", " ").splitlines():
                if re.search(r"\bdocker\b.*\bbuild\b", line) and re.search(r"(^|\s)--label(=|\s|$)", line):
                    fail(f"the `{job_name}` job's step `{name}` passes `--label` to `docker build` — a passed label "
                         f"overrides the target stage's")
    if not labelled_targets:
        fail(f"no published target stage was found to hold to its labels, so assertion 12 checked NOTHING")


# ---- 3. a publish refuses to replace a release, and leaves one ref -------------------------------------------

if workflow_doc:
    # `on:` is a YAML 1.1 boolean, so PyYAML keys it as True. Enumerate the spelling rather than guess it.
    triggers = workflow_doc.get("on", workflow_doc.get(True)) or {}
    if (triggers.get("push") or {}).get("tags"):
        fail(
            f"{WORKFLOW.relative_to(ROOT)} triggers on a pushed tag. The pipeline CREATES `snapshot-<major>.<minor>`, "
            f"so a tag trigger could only re-run a commit whose snapshot exists — which the `{REFUSE_STEP}` step "
            f"refuses by design."
        )
    # A dispatch is a snapshot now, exactly as the weekly schedule makes one: an input would be a second kind of publish,
    # and the release kind left this file for the promotion (ADR 0091 decision 5).
    dispatch_inputs = (triggers.get("workflow_dispatch") or {}).get("inputs") if isinstance(
        triggers.get("workflow_dispatch"), dict) else None
    if "workflow_dispatch" not in triggers:
        fail(f"{WORKFLOW.relative_to(ROOT)} has no `workflow_dispatch` — a snapshot now, a release branch's fix "
             f"above all (the schedule runs on `main` alone), has no other way to be published")
    elif dispatch_inputs:
        fail(
            f"{WORKFLOW.relative_to(ROOT)}: `workflow_dispatch` takes inputs ({', '.join(map(str, dispatch_inputs))}) — a "
            f"dispatch publishes a snapshot exactly as the schedule does; a release is a promotion in a workflow of its own "
            f"(ADR 0091 decision 5), never a kind this pipeline builds"
        )

    def gated_on(condition: object, conjunct: str) -> bool:
        """Whether a GitHub `if:` (or an expression input) cannot be true unless `conjunct` is.

        Deliberately conservative: the expression must be a pure `&&` chain with the conjunct as one of its
        terms. Any `||` or a negation could reach the step without it, and is read as NOT gated — a false red,
        never a false green.
        """
        text = str(condition if condition is not None else "").strip()
        if text.startswith("${{") and text.endswith("}}"):
            text = text[3:-2].strip()
        if not text or "||" in text:
            return False
        terms = [" ".join(term.split()) for term in text.split("&&")]
        # `!cancelled()` is the one negation allowed: it lifts GitHub's implicit `success()` (assertion 16) and can
        # only narrow when the job runs, never let it run without the conjunct.
        if any(re.search(r"!(?!=)", term) for term in terms if term != "!cancelled()"):
            return False
        return conjunct in terms

    jobs = workflow_doc.get("jobs") or {}
    publish_job = jobs.get(PUBLISH_JOB) or {}
    tag_job = jobs.get(SNAPSHOT_TAG_JOB) or {}
    if not publish_job:
        fail(f"{WORKFLOW.relative_to(ROOT)} has no `{PUBLISH_JOB}` job — nothing publishes a green run's snapshot")
    if not tag_job:
        fail(f"{WORKFLOW.relative_to(ROOT)} has no `{SNAPSHOT_TAG_JOB}` job — no snapshot gets its git tag, so GitHub's "
             f"release page offers nothing to promote (ADR 0091 decision 5)")

    # The publish signs and writes the registry; the one write to the repository is the tag job's, which holds that
    # permission and NOTHING else (owner 2026-11-06): a job that signs and pushes refs is the one a compromise of any
    # step in it turns into both.
    publish_perms = publish_job.get("permissions") or {}
    if publish_perms.get("contents") == "write" or publish_perms.get("packages") != "write" \
            or publish_perms.get("id-token") != "write":
        fail(
            f"the `{PUBLISH_JOB}` job's permissions are {publish_perms or 'the default'} — it needs `packages: write` and "
            f"`id-token: write` (the registry, keyless signing) and must not hold `contents: write`, which is the "
            f"`{SNAPSHOT_TAG_JOB}` job's alone"
        )
    tag_perms = tag_job.get("permissions")
    if tag_job and tag_perms != {"contents": "write"}:
        fail(
            f"the `{SNAPSHOT_TAG_JOB}` job's permissions are {tag_perms or 'the default'}, not exactly "
            f"`contents: write` — it writes the snapshot's git tag and holds nothing else (ADR 0091 decision 5)"
        )
    raw_tag_needs = tag_job.get("needs") or []
    tag_needs = [raw_tag_needs] if isinstance(raw_tag_needs, str) else [str(n) for n in raw_tag_needs]
    if tag_job and PUBLISH_JOB not in tag_needs:
        fail(f"the `{SNAPSHOT_TAG_JOB}` job does not need `{PUBLISH_JOB}` — a tag would offer a snapshot that was never "
             f"published for promotion")
    tag_if = " ".join(str(tag_job.get("if") or "").split())
    if tag_job and (not gated_on(tag_job.get("if"), PUBLISHES)
                    or re.search(r"\b(?:always|failure)\s*\(", tag_if)
                    or f"needs.{PUBLISH_JOB}.result == 'success'" not in tag_if):
        fail(f"the `{SNAPSHOT_TAG_JOB}` job runs when `{PUBLISHES}` is not true, or does not require "
             f"`needs.{PUBLISH_JOB}.result == 'success'` (if: `{tag_job.get('if')}`) — a tag would offer a snapshot "
             f"that was never published")

    for job_name, step_id, must_contain, why in (
        (
            PUBLISH_JOB, REFUSE_STEP,
            (f"{SNAPSHOT_SCRIPT} check-unpublished", '--version "$VERSION"', '--commit "$GITHUB_SHA"'),
            "a snapshot is written once per commit and never replaced: a name that holds another build must FAIL the "
            "publish, and a registry error must not read as `nothing published yet` (ADR 0091 decision 5, ADR 0057 §1)",
        ),
        (
            SNAPSHOT_TAG_JOB, TAG_STEP,
            ('tag="snapshot-${VERSION}"', 'refs/tags/${tag}', '"repos/${GITHUB_REPOSITORY}/git/refs"', "--method POST"),
            "every snapshot gets the git tag `snapshot-<major>.<minor>` on its commit, from the tree-derived version "
            "(ADR 0091 decision 5)",
        ),
    ):
        step = step_by_id(workflow_doc, job_name, step_id)
        if step is None:
            fail(f"{WORKFLOW.relative_to(ROOT)}: no step `id: {step_id}` in job `{job_name}` — {why}")
            continue
        body = str(step.get("run") or "")
        if (step.get("env") or {}).get("VERSION") != VERSION_EXPR:
            fail(
                f"the `{step_id}` step's `VERSION` is not `{VERSION_EXPR}` — it must key on the version the tree "
                f"derived, never on a registry query or a hand-typed literal"
            )
        for needle in must_contain:
            if needle not in body:
                fail(f"the `{step_id}` step does not contain `{needle}` — {why}")
        if "--force" in body or re.search(r"\s-f\s", body):
            fail(f"the `{step_id}` step forces: a snapshot that can be overwritten names a moving artifact (ADR 0091 "
                 f"decision 5)")
    # The refusal runs before anything this job writes, or it refuses after the names have moved.
    publish_steps = job_steps(workflow_doc, PUBLISH_JOB)
    refuse_at = next((i for i, s in enumerate(publish_steps) if s.get("id") == REFUSE_STEP), None)
    first_write = next((i for i, s in enumerate(publish_steps)
                        if FIRST_PUBLISH_WRITE.search(str(s.get("run") or ""))), None)
    if refuse_at is not None and first_write is not None and first_write < refuse_at:
        fail(f"the `{PUBLISH_JOB}` job writes (step `{publish_steps[first_write].get('name')}`) before its `{REFUSE_STEP}` "
             f"step — the refusal would come after a published name already moved")

    # The only push to the repository is the tag job's, and it never pushes a release's name: `v…` says "released"
    # (ADR 0057 §2), and every tag watcher reads a `v` tag as a release.
    text = WORKFLOW.read_text()
    if re.search(r"refs/tags/v|\bgit\s+tag\s+\"?v", text):
        fail(f"{WORKFLOW.relative_to(ROOT)} names a `v…` tag — a snapshot's tag is never `v…`; that name is a promotion's "
             f"(ADR 0091 decision 5)")
    for job_name in sorted(jobs):
        for step in job_steps(workflow_doc, job_name):
            if GIT_PUSH.search(str(step.get("run") or "")) and job_name != SNAPSHOT_TAG_JOB:
                fail(f"job `{job_name}`, step `{step.get('name') or step.get('id')}` writes a git ref — the one write to "
                     f"the repository is the `{SNAPSHOT_TAG_JOB}` job's, which holds `contents: write` and nothing else")
            if GH_RELEASE_CREATE.search(str(step.get("run") or "")) or RELEASE_ASSET_WRITE.search(str(step.get("run") or "")) \
                    or RELEASE_ACTION.search(str(step.get("uses") or "").split("@", 1)[0]):
                fail(f"job `{job_name}`, step `{step.get('name') or step.get('id')}` writes a GitHub Release — a "
                     f"snapshot gets none (ADR 0096 decision 3); a release is a promotion, in a workflow of its own")

    # Every write is reachable only from a publishing run — never a pull request, never a local run. The set is derived
    # from what the steps DO — a login or build-push action, or a `run:` that pushes, logs in, signs or attests — never
    # listed, so a write added to any job is held without anyone remembering to add it here.
    registry_writes = 0
    for job_name, job in sorted(jobs.items()):
        if not isinstance(job, dict):
            continue
        job_gated = gated_on(job.get("if"), PUBLISHES)
        for step in job_steps(workflow_doc, job_name):
            uses = str(step.get("uses") or "")
            writes = []
            if uses.startswith("docker/login-action@"):
                writes.append("logs in to a registry")
            if uses.startswith("docker/build-push-action@"):
                push = (step.get("with") or {}).get("push")
                if push is not False and str(push).lower() != "false":
                    writes.append("pushes an image")
                    if gated_on(push, PUBLISHES):
                        registry_writes += 1
                        writes.pop()
            match = REGISTRY_WRITE_RUN.search(str(step.get("run") or ""))
            if match:
                writes.append(f"runs `{match.group(0)}`")
            registry_writes += len(writes)
            if writes and not (job_gated or gated_on(step.get("if"), PUBLISHES)):
                fail(
                    f"job `{job_name}`, step `{step.get('name') or step.get('id')}` {' and '.join(writes)} when "
                    f"`{PUBLISHES}` is not true — a pull request (Dependabot's included) and a local run write to no "
                    f"registry and no remote. Gate the step, its job, or the push input on `{PUBLISHES}`."
                )
    if registry_writes == 0:
        fail(
            f"{WORKFLOW.relative_to(ROOT)} has no registry write at all that this guard recognises — the gating "
            f"assertion would then hold NOTHING and report green"
        )

    # ---- 9. the publish waits on every stage ------------------------------------------------------------------
    # Every other job of the file is a stage (ADR 0091 decision 2), read from the file rather than listed, so a stage
    # added later is held without anyone remembering this guard.
    raw_needs = publish_job.get("needs") or []
    publish_needs = [raw_needs] if isinstance(raw_needs, str) else [str(n) for n in raw_needs]

    def needs_of(name: str) -> set[str]:
        raw = (jobs.get(name) or {}).get("needs") or []
        return {raw} if isinstance(raw, str) else {str(n) for n in raw}

    # The jobs after the publish (the snapshot's tag) are not stages it waits on: found by needing it, never listed.
    downstream: set[str] = set()
    grew = True
    while grew:
        grew = False
        for name in jobs:
            if name not in downstream and name != PUBLISH_JOB and needs_of(name) & (downstream | {PUBLISH_JOB}):
                downstream.add(name)
                grew = True
    stage_jobs = sorted(str(j) for j, v in jobs.items()
                        if j != PUBLISH_JOB and j not in downstream and isinstance(v, dict))
    if publish_job and not stage_jobs:
        fail(f"{WORKFLOW.relative_to(ROOT)} has no job besides `{PUBLISH_JOB}` — assertion 9 checked NOTHING")
    for stage in stage_jobs:
        if stage not in publish_needs:
            fail(
                f"the `{PUBLISH_JOB}` job does not need `{stage}` — the build starts beside the tests, so the "
                f"publish's `needs:` is the only thing that keeps an image of a commit whose `{stage}` is red from "
                f"being published (ADR 0091 decision 2). Every other job of the file is a stage it waits on."
            )
    publish_if = str(publish_job.get("if") or "").strip()
    if publish_if.startswith("${{") and publish_if.endswith("}}"):
        publish_if = publish_if[3:-2].strip()
    if re.search(r"\b(?:always|failure|cancelled)\s*\(\s*\)", publish_if):
        # A status function lifts GitHub's implicit `success()`, so a failed or skipped need no longer stops the job
        # by itself: the condition has to say, need by need, what it accepts.
        terms = {" ".join(t.split()) for t in publish_if.split("&&")}
        for need in publish_needs:
            accepted = f"needs.{need}.result == 'success'" in terms
            need_job = jobs.get(need) or {}
            if not accepted and (need_job.get("if") is False or str(need_job.get("if")).strip() == "false"):
                accepted = f"contains(fromJSON('[\"success\", \"skipped\"]'), needs.{need}.result)" in terms
            if not accepted:
                fail(
                    f"the `{PUBLISH_JOB}` job's `if:` calls a status function, which lets it run past a failed "
                    f"need, but it does not require `needs.{need}.result == 'success'` as one of its `&&` terms — a "
                    f"publish would go ahead on a red `{need}` (ADR 0091 decision 2). Only a job that is literally "
                    f"`if: false` may be accepted as `skipped` too."
                )

    # ---- 10. the snapshot waits on lane L1 ----------------------------------------------------------------------
    # Assertion 9 holds the publish to every job the file HAS; it cannot see a job that is gone. Lane L1 is the one
    # stage that installs the candidate as a Server Owner does (ADR 0090 decision 2), so its job is found by what it
    # runs — the orchestrator — never by its name, and must exist, follow the build, cover every architecture the
    # build publishes, and be waited on with `success`.
    lane_jobs = sorted(
        str(name) for name, job in jobs.items()
        if isinstance(job, dict) and any(LANE_ORCHESTRATOR in str(s.get("run") or "") for s in job_steps(workflow_doc, name))
    )
    if not lane_jobs:
        fail(
            f"no job of {WORKFLOW.relative_to(ROOT)} runs `{LANE_ORCHESTRATOR}` — lane L1 is gone, and the `{PUBLISH_JOB}` "
            f"job would publish images no stack ever booted from their own release (ADR 0090 decision 2)"
        )
    build_archs = {str(a) for a in (((jobs.get(BUILD_JOB) or {}).get("strategy") or {}).get("matrix") or {}).get("arch") or []}
    for lane in lane_jobs:
        lane_job = jobs[lane]
        raw = lane_job.get("needs") or []
        lane_needs = [raw] if isinstance(raw, str) else [str(n) for n in raw]
        if BUILD_JOB not in lane_needs:
            fail(f"lane L1's job `{lane}` does not need `{BUILD_JOB}` — on a publishing run it installs the images that "
                 f"job pushed, so it must run after it (ADR 0090 decision 2)")
        if lane not in publish_needs or f"needs.{lane}.result == 'success'" not in {
                " ".join(t.split()) for t in publish_if.split("&&")}:
            fail(f"the `{PUBLISH_JOB}` job does not wait on lane L1 (`{lane}`) with `needs.{lane}.result == 'success'` "
                 f"— the snapshot would be published on bytes the lane never installed (ADR 0090 decision 2)")
        lane_archs = {str(a) for a in (((lane_job.get("strategy") or {}).get("matrix") or {}).get("arch") or [])}
        if not build_archs:
            fail(f"the `{BUILD_JOB}` job's matrix names no `arch` — lane L1's architectures cannot be held to it")
        elif not build_archs <= lane_archs:
            fail(f"lane L1's job `{lane}` runs on {sorted(lane_archs)}, the `{BUILD_JOB}` publishes {sorted(build_archs)} "
                 f"— what is published for an architecture must have passed on it (ADR 0091 decision 4)")

    # ---- 13. each lane's result is attested, per architecture, by the publish and never by the lane --------------
    # ADR 0091 decision 5: every snapshot is published with each lane's result as a signed attestation
    # (lane, architecture, outcome, inputs). A leg records what it ran; the publish assembles one predicate per
    # architecture from every leg the lane's matrix runs — the `--arch` and `--shard` it passes are that matrix, read
    # here, never listed — and attests it with the type `scripts/snapshot.py` names, after the signatures. The leg runs
    # the commit's test code, so it holds no `id-token`.
    sys.path.insert(0, str(ROOT / "scripts"))
    import snapshot as snapshot_script  # noqa: E402 — the predicate type the promotion verifies by

    def argv_lines(job_name: str, needle: str) -> list[tuple[int, dict, list[str]]]:
        found = []
        for index, step in enumerate(job_steps(workflow_doc, job_name)):
            body = str(step.get("run") or "").replace("\\\n", " ")
            for line in body.splitlines():
                if needle in line:
                    try:
                        found.append((index, step, shlex.split(line.split(" #", 1)[0])))
                    except ValueError as e:
                        fail(f"job `{job_name}`: cannot parse `{line.strip()[:80]}`: {e}")
        return found

    for lane in lane_jobs:
        matrix = ((jobs[lane].get("strategy") or {}).get("matrix") or {})
        lane_archs = sorted(str(a) for a in matrix.get("arch") or [])
        lane_shards = sorted(str(d) for d in matrix.get("domain") or [])
        if "id-token" in (jobs[lane].get("permissions") or {}):
            fail(f"lane L1's job `{lane}` holds `id-token` — a leg runs the commit's test code and signs nothing; the "
                 f"`{PUBLISH_JOB}` job attests its result")
        records = [(i, s) for i, s, argv in argv_lines(lane, f"{SNAPSHOT_SCRIPT} lane-result")]
        if not records:
            fail(f"lane L1's job `{lane}` records no result (`{SNAPSHOT_SCRIPT} lane-result`) — the snapshot would carry "
                 f"no attestation of what the lane ran (ADR 0091 decision 5)")
        for _, step in records:
            if not gated_on(step.get("if"), PUBLISHES):
                fail(f"lane L1's job `{lane}` records its result when `{PUBLISHES}` is not true — a pull request pulled "
                     f"no digest to record")
        assembled = argv_lines(PUBLISH_JOB, f"{SNAPSHOT_SCRIPT} predicates")
        if len(assembled) != 1:
            fail(f"the `{PUBLISH_JOB}` job runs `{SNAPSHOT_SCRIPT} predicates` {len(assembled)} time(s), not once")
            continue
        assembled_at, _, argv = assembled[0]
        if sorted(flag_values(argv, "--arch")) != lane_archs or sorted(flag_values(argv, "--shard")) != lane_shards:
            fail(
                f"the `{PUBLISH_JOB}` job assembles the attestations for architectures {sorted(flag_values(argv, '--arch'))} "
                f"and shards {sorted(flag_values(argv, '--shard'))}, while lane `{lane}` runs {lane_archs} and "
                f"{lane_shards} — a leg the attestation does not expect is unattested, and an expected leg that never runs "
                f"refuses every snapshot"
            )
        attests = [(i, s) for i, s in enumerate(job_steps(workflow_doc, PUBLISH_JOB))
                   if COSIGN_ATTEST.search(str(s.get("run") or ""))]
        signs = [i for i, s in enumerate(job_steps(workflow_doc, PUBLISH_JOB))
                 if re.search(r"\bcosign\s+sign\s", str(s.get("run") or ""))]
        if not attests:
            fail(f"the `{PUBLISH_JOB}` job runs no `cosign attest` — the lanes' results are assembled and never attested")
        for index, step in attests:
            env = {str(k): str(v) for k, v in (step.get("env") or {}).items()}
            if env.get("PREDICATE_TYPE") != snapshot_script.PREDICATE_TYPE or '--type "$PREDICATE_TYPE"' not in str(step.get("run")):
                fail(f"the `{PUBLISH_JOB}` job's attest step does not attest with `--type \"$PREDICATE_TYPE\"` set to "
                     f"`{snapshot_script.PREDICATE_TYPE}` (found `{env.get('PREDICATE_TYPE')}`) — the type is what a "
                     f"promotion verifies by")
            if index < assembled_at or not signs or index < max(signs):
                fail(f"the `{PUBLISH_JOB}` job attests before it assembles the predicates or before it signs the images "
                     f"— the attestation's subject is the signed index digest")

    # The cosign that signs is the line operators verify with (inbox #474): one installer, before every `cosign`, at an
    # exact version no older than the floor `veyqctl` refuses below — the installer's default lags (v4.1.2: v3.0.6).
    installers = [
        (job_name, index, step)
        for job_name in sorted(jobs)
        for index, step in enumerate(job_steps(workflow_doc, job_name))
        if str(step.get("uses") or "").split("@", 1)[0] == COSIGN_INSTALLER
    ]
    cosign_runs = [
        (job_name, index, step, line.strip())
        for job_name in sorted(jobs)
        for index, step in enumerate(job_steps(workflow_doc, job_name))
        for line in str(step.get("run") or "").replace("\\\n", " ").splitlines()
        if COSIGN_RUN.search(line.split(" #", 1)[0])
    ]
    cosign_floor = veyqctl_constants(r"COSIGN_MIN_VERSION").get("COSIGN_MIN_VERSION") or ""
    floor_parts = tuple(int(x) for x in cosign_floor.split(".")) if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", cosign_floor) else None
    if not cosign_runs:
        fail(f"{WORKFLOW.relative_to(ROOT)} runs no `cosign` at all, so the check of the cosign that signs checked NOTHING")
    if floor_parts is None:
        fail(f"src/tools/veyqctl.py assigns no `COSIGN_MIN_VERSION` as `<major>.<minor>.<patch>` (found {cosign_floor!r}) "
             f"— the floor the publish's cosign is held to is the one `veyqctl` verifies with")
    if len(installers) != 1 or installers[0][0] != PUBLISH_JOB:
        fail(
            f"{WORKFLOW.relative_to(ROOT)} uses `{COSIGN_INSTALLER}` in {len(installers)} step(s) "
            f"({', '.join(j for j, _, _ in installers) or 'none'}) — the publish installs the cosign it signs with "
            f"exactly once, in the `{PUBLISH_JOB}` job, so there is one answer to which cosign wrote a bundle"
        )
    else:
        _, installer_at, installer = installers[0]
        cosign_release = str((installer.get("with") or {}).get("cosign-release") or "")
        signing_cosign = cosign_release
        pinned = EXACT_VERSION.match(cosign_release)
        if not pinned:
            fail(
                f"the `{COSIGN_INSTALLER}` step's `cosign-release` is {cosign_release or 'unset'!r}, not an exact "
                f"`v<major>.<minor>.<patch>` — unset, the installer installs its own default, which lags (v4.1.2 "
                f"installs v3.0.6, a cosign `veyqctl` refuses: GHSA-fx35-mq7g-6g98)"
            )
        elif floor_parts and tuple(int(x) for x in pinned.groups()) < floor_parts:
            fail(
                f"the `{COSIGN_INSTALLER}` step installs cosign {cosign_release}, older than {cosign_floor}, the oldest "
                f"`veyqctl` (src/tools/veyqctl.py COSIGN_MIN_VERSION) and the Run page verify with — the publish would "
                f"sign with a cosign its own operators are told to refuse"
            )
        for job_name, index, step, line in cosign_runs:
            if job_name != PUBLISH_JOB or index < installer_at:
                where = f"outside the `{PUBLISH_JOB}` job" if job_name != PUBLISH_JOB else "before the installer"
                fail(
                    f"job `{job_name}`, step `{step.get('name') or step.get('id')}` runs `cosign` {where} — it would "
                    f"run whichever cosign the runner has, not the pinned one"
                )
    for job_name, index, step, line in cosign_runs:
        if NEW_BUNDLE_FORMAT in line:
            fail(
                f"job `{job_name}`, step `{step.get('name') or step.get('id')}` passes `{NEW_BUNDLE_FORMAT}` "
                f"(`{line[:80]}`) — cosign 3 deprecates it: the Sigstore bundle is its default and will be its only "
                f"format, so the flag can only break a later cosign, and its `=false` spelling writes the legacy "
                f"bundle every cosign up to 3.1.2 accepts forged (GHSA-fx35-mq7g-6g98)"
            )


# ---- 4. the release carries every file its recipe reads; written-down tags name a shape the pipeline emits --

recipe_assets: list[str] = []
reads, refusals = release_assets.recipe_reads()
for refusal in refusals:
    fail(f"the release recipe cannot be derived: {refusal}")
if not refusals:
    # asset -> (its file in the tree, why the release carries it)
    derived = {
        release_assets.COMPOSE.name: (release_assets.COMPOSE, "the recipe itself"),
        release_assets.TEMPLATE_ASSET: (release_assets.ENV_TEMPLATE, "the .env template"),
    }
    derived.update({asset: (release_assets.COMPOSE.parent / asset, ", ".join(wheres)) for asset, wheres in reads.items()})
    with tempfile.TemporaryDirectory(prefix="release_assets_") as tmp:
        carried = pathlib.Path(tmp)
        try:
            release_assets.write_assets(carried)
        except (release_assets.ReleaseError, OSError) as e:
            fail(f"the producer's carry step failed, so no release can be produced: {e}")
        else:
            for asset, (source, why) in sorted(derived.items()):
                got = carried / asset
                if not got.is_file():
                    fail(
                        f"scripts/release_assets.py does not carry `{asset}`, which the release recipe reads "
                        f"({why}) — an operator would install a recipe that reads a file its release does not have"
                    )
                elif got.read_bytes() != source.read_bytes():
                    fail(f"scripts/release_assets.py carries a `{asset}` that differs from {source.relative_to(ROOT)}")
                else:
                    recipe_assets.append(asset)
            # `veyqctl` fetches the assets it names, by name: each must be one the producer writes, or an install
            # from a real release misses it (the template's name moved once, to `env.example`, for GitHub's sake).
            # The manifest and the two bundles are written beside the carried files (the bundles by the publish's
            # `cosign sign-blob`, whose names the producer reserves), so they are names a release has too.
            produced = {p.name for p in carried.iterdir()} | {
                release_assets.MANIFEST, release_assets.VEYQCTL_BUNDLE, release_assets.MANIFEST_BUNDLE}
            # Underscores in the prefix too: `[A-Z]+_ASSET` never saw `MANIFEST_BUNDLE_ASSET`, and an anchor set
            # found by a name pattern loses a constant silently, green (cr-signed-release-manifest).
            veyqctl_assets = veyqctl_constants(r"[A-Z][A-Z_]*_ASSET")
            if not veyqctl_assets:
                fail("src/tools/veyqctl.py names no `*_ASSET` constant, so the names it fetches checked NOTHING")
            # The manifest's bundle by name, not merely a name a release has: `veyqctl.bundle` is one too, and a
            # `veyqctl` that verified the manifest against it would refuse every published release.
            if veyqctl_assets.get("MANIFEST_BUNDLE_ASSET") != release_assets.MANIFEST_BUNDLE:
                fail(
                    f"src/tools/veyqctl.py's `MANIFEST_BUNDLE_ASSET` is `{veyqctl_assets.get('MANIFEST_BUNDLE_ASSET')}`, not "
                    f"`{release_assets.MANIFEST_BUNDLE}`, the manifest's bundle the publish writes — `veyqctl` would "
                    f"verify the manifest against another file, or refuse every release for the lack of it"
                )
            for constant, name in sorted(veyqctl_assets.items()):
                if name not in produced:
                    fail(
                        f"src/tools/veyqctl.py's `{constant}` is `{name}`, which scripts/release_assets.py does not write "
                        f"(it writes {', '.join(sorted(produced))}) — `veyqctl install` would miss it in every release"
                    )

written_tags = 0
# Tracked files only, as in assertion 9's flag scan. A gitignored `infra/docker/.env` is one operator's local pin (a
# dev build's `veyq:cr162`, say), not something this repository tells anyone to run — scanning it made the
# guard red on that machine and green in CI over the same commit.
RUN_PAGES = ROOT / "site/src/content/docs/run"  # the operator procedures (ADR 0087), which write tags down too
tracked_infra = subprocess.run(
    ["git", "ls-files", "-z", "--", *map(repo_layout.rel, TAG_SCAN_DIRS), repo_layout.rel(RUN_PAGES)],
    cwd=ROOT, capture_output=True, text=True,
)
if tracked_infra.returncode != 0:
    fail(
        "`git ls-files` failed, so this guard cannot enumerate the tracked deployment files and its tag scan "
        f"checked NOTHING ({tracked_infra.stderr.strip()[-200:]})."
    )
for path in sorted(ROOT / rel for rel in tracked_infra.stdout.split("\0") if rel):
    if not path.is_file():
        continue  # tracked but deleted in the working tree
    try:
        text = path.read_text()
    except (UnicodeDecodeError, OSError):
        continue
    for match in IMAGE_TAG.finditer(text):
        # `<tag>@sha256:<digest>` is how a release is deployed (ADR 0057 §3); the tag is the half that makes a claim
        # about the pipeline's shapes, and the digest's own form is validate_image_pins.py's business.
        tag = match.group(1).split("@", 1)[0]
        if tag.startswith("${") or "<" in tag:
            continue  # a variable or a placeholder, not a claim about a tag that exists
        written_tags += 1
        if not RELEASE_VERSION.match(tag):
            fail(
                f"{path.relative_to(ROOT)}: `veyq:{tag}` is not a tag this pipeline emits. A publish produces "
                f"`<major>`, `<major>.<minor>`, `<major>.<minor>-SNAPSHOT`, `sha-<sha>` and `latest`; of those only "
                f"a release's `<major>.<minor>` names a fixed set of bits, which is the only kind of tag worth writing down as the one an "
                f"operator pins and rolls back to (ADR 0056 §6)."
            )

# ---- 5. the release recipe runs only images an operator can PULL ---------------------------------------------

PROD_COMPOSE = repo_layout.PROD_COMPOSE

# A veyq-namespaced image name where an image reference would carry one: at the start of the value, or after a
# registry/owner `/`, and ending at the tag separator or the value's end. Deliberately NOT anchored on the whole
# value — the recipe's veyq images arrive as `${VEYQ_IMAGE:?…, e.g. ghcr.io/<owner>/veyq:1.0}`, so the only
# place the NAME appears is inside that error message, and a whole-value match would see none of them.
VEYQ_IMAGE_NAME = re.compile(r"(?:^|[\s/])(veyq(?:-[a-z0-9]+)*)(?=[:@\s}]|$)")


def published_images(workflow_text: str) -> set[str]:
    """The image names a publish actually emits, read out of the build matrix rather than listed here.

    Listing them would make this assertion only as complete as the day it was written: a third published image
    would be a name nobody asserted rather than a broken assertion (§ *a guard derives its anchor set from the
    code*, changes/README.md).
    """
    doc = yaml.safe_load(workflow_text) or {}
    matrix = ((doc.get("jobs") or {}).get(BUILD_JOB) or {}).get("strategy", {}).get("matrix", {})
    return {str(i) for i in (matrix.get("image") or [])}


if not PROD_COMPOSE.is_file():
    fail(f"`{PROD_COMPOSE.relative_to(ROOT)}` is missing — there is no release recipe to check")
elif not WORKFLOW.is_file():
    fail(f"`{WORKFLOW.relative_to(ROOT)}` is missing — there is nothing to derive the published image set from")
else:
    publishable = published_images(WORKFLOW.read_text())
    if not publishable:
        fail(
            f"{WORKFLOW.relative_to(ROOT)}'s `{BUILD_JOB}` job declares no `strategy.matrix.image` — this "
            f"assertion would then accept every image name and report green while checking NOTHING"
        )
    try:
        recipe_doc = yaml.safe_load(PROD_COMPOSE.read_text()) or {}
    except yaml.YAMLError as e:  # pragma: no cover - reported by assertion 6 too
        recipe_doc = {}
        fail(f"{PROD_COMPOSE.relative_to(ROOT)} is not readable YAML: {e}")

    checked_services = 0
    for name, service in sorted((recipe_doc.get("services") or {}).items()):
        if not isinstance(service, dict):
            continue
        checked_services += 1
        if "build" in service:
            fail(
                f"{PROD_COMPOSE.relative_to(ROOT)}: service `{name}` carries a `build:` stanza. The release "
                f"recipe must BUILD NOTHING — Compose hides this by building on the operator's host, and a "
                f"Helm chart (ADR 0064) cannot build at all, so the service simply has nothing to pull. "
                f"Publish the image, or move the work into something that is already published."
            )
        for veyq_name in VEYQ_IMAGE_NAME.findall(str(service.get("image", ""))):
            if publishable and veyq_name not in publishable:
                fail(
                    f"{PROD_COMPOSE.relative_to(ROOT)}: service `{name}` names veyq image `{veyq_name}`, which "
                    f"{WORKFLOW.relative_to(ROOT)} does not publish (it publishes "
                    f"{', '.join(sorted(publishable))}). An operator cannot pull it and a chart cannot build it."
                )
    if checked_services == 0:
        # A recipe with no services and a recipe whose services are all fine report the same green.
        fail(f"{PROD_COMPOSE.relative_to(ROOT)} declares no services — this assertion checked NOTHING")


if workflow_doc:
    # ---- 8. the chart is the snapshot's third artifact ----------------------------------------------------------
    chart_meta = yaml.safe_load((CHART_DIR / "Chart.yaml").read_text()) if (CHART_DIR / "Chart.yaml").is_file() else {}
    chart_name = str((chart_meta or {}).get("name") or "")
    owner_expr = "ghcr.io/${{ github.repository_owner }}"
    refuse = step_by_id(workflow_doc, PUBLISH_JOB, REFUSE_STEP) or {}
    try:
        refuse_argv = shlex.split(str(refuse.get("run") or "").replace("\\\n", " "))
    except ValueError:
        refuse_argv = []
    matrix_images = sorted(published_images(WORKFLOW.read_text())) if WORKFLOW.is_file() else []
    if not chart_name:
        fail(f"{(CHART_DIR / 'Chart.yaml').relative_to(ROOT)} names no chart, so the chart's snapshot cannot be held")
    if (refuse.get("env") or {}).get("REGISTRY") != owner_expr:
        fail(f"the `{REFUSE_STEP}` step's `REGISTRY` is not `{owner_expr}` — it would check another registry's names")
    for image in matrix_images:
        if image not in flag_values(refuse_argv, "--image"):
            fail(
                f"the `{REFUSE_STEP}` step does not check `--image {image}` — a snapshot with that image's names already "
                f"taken would be published around it, and a snapshot whose images disagree is a partial one (ADR 0065)"
            )
    if f"charts/{chart_name}" not in flag_values(refuse_argv, "--chart"):
        fail(f"the `{REFUSE_STEP}` step does not check `--chart charts/{chart_name}` — the chart's snapshot name would be "
             f"written over (ADR 0064)")
    package_steps = [s for s in job_steps(workflow_doc, PUBLISH_JOB) if "helm package" in str(s.get("run") or "")]
    if len(package_steps) != 1:
        fail(
            f"the `{PUBLISH_JOB}` job has {len(package_steps)} step(s) running `helm package`; the chart ships "
            f"with every snapshot, packaged exactly once (ADR 0064)"
        )
    for step in package_steps:
        body = str(step.get("run") or "")
        env = {str(k): str(v) for k, v in (step.get("env") or {}).items()}
        if env.get("VERSION") != VERSION_EXPR:
            fail(
                f"the chart packaging step's `VERSION` is `{env.get('VERSION')}`, not `{VERSION_EXPR}` — the "
                f"chart's version must be the tree's, never the in-tree placeholder or a literal"
            )
        # `-SNAPSHOT` on both: `appVersion` is the image tag the values default to, and the snapshot's images are
        # tagged `<version>-SNAPSHOT` — the release's chart, packaged by the promotion, names `<version>`.
        for flag in ('--version "${VERSION}-SNAPSHOT"', '--app-version "${VERSION}-SNAPSHOT"'):
            if flag not in body:
                fail(
                    f"the chart packaging step does not pass `{flag}` — the snapshot chart's `version` and `appVersion` "
                    f"are both the snapshot's name (ADR 0064); `appVersion` is the image tag the values default to, so "
                    f"a chart packaged otherwise deploys images this snapshot does not name"
                )
        if not re.search(r"helm\s+push\b", body) or f"oci://{owner_expr}/charts" not in str(step.get("env") or {}) + body:
            fail(
                f"the chart packaging step does not `helm push` to `oci://{owner_expr}/charts` — `helm push` "
                f"appends the chart's name, so that target is what puts it at `charts/{chart_name}` and not on "
                f"the app image's own repository (ADR 0064, measured with Helm 4.0.1)"
            )
    signs_chart = any(
        re.search(r"cosign\s+sign\b", str(s.get("run") or ""))
        and f"{owner_expr}/charts/{chart_name}" in str(s.get("env") or {}) + str(s.get("run") or "")
        for s in job_steps(workflow_doc, PUBLISH_JOB)
    )
    if chart_name and not signs_chart:
        fail(
            f"no `{PUBLISH_JOB}` step runs `cosign sign` over `{owner_expr}/charts/{chart_name}` — the chart is "
            f"verified the way the images are ({VERIFY_PAGE.relative_to(ROOT)}), and an unsigned one "
            f"verifies against nothing"
        )


# ---- 6. the production project is declared by the file, and no state instructs the flag it replaced ---------
# The flow surfaces. They record what was decided and retired, so a retired rule's name necessarily survives
# there — excluding them is the repository's own state/flow line, not a list of files that happened to be red.
FLOW_PREFIXES = ("changes/", "docs/inbox.md")

prod_project: str | None = None
if not PROD_COMPOSE.is_file():
    fail(f"`{PROD_COMPOSE.relative_to(ROOT)}` is missing — there is no production stack to name a project for")
else:
    try:
        prod_doc = yaml.safe_load(PROD_COMPOSE.read_text()) or {}
    except yaml.YAMLError as e:
        prod_doc = {}
        fail(f"{PROD_COMPOSE.relative_to(ROOT)} is not readable YAML: {e}")
    prod_project = prod_doc.get("name") if isinstance(prod_doc.get("name"), str) else None
    if not prod_project:
        fail(
            f"{PROD_COMPOSE.relative_to(ROOT)} declares no top-level `name:`. Compose then derives the project "
            f"from the file's DIRECTORY — `{PROD_COMPOSE.parent.name}` — which the dev compose file shares, so "
            f"an unqualified `down -v` reaches the production volumes and `src/tools/instance_backup.py`'s "
            f"`docker compose … exec` (which emits no `-p`) aims the documented production capture at whatever "
            f"runs out of that directory. On a host that also runs the dev stack it does not fail: it captures "
            f"the wrong instance and reports success."
        )

if prod_project:
    # The project flag in either spelling, carrying the declared name, wherever it is written — a fenced
    # command and a prose sentence are the same instruction, and the sentence is the shape this rule actually
    # took before the file carried it. Built from `prod_project` rather than typed, which is also what keeps
    # this line from being a copy of the very literal it forbids.
    flag = re.compile(rf"(?<![\w-])(?:-p|--project-name)[ =]\s*{re.escape(prod_project)}(?![\w.-])")
    tracked = subprocess.run(["git", "ls-files"], cwd=ROOT, capture_output=True, text=True)
    if tracked.returncode != 0:
        fail(
            "`git ls-files` failed, so this guard cannot enumerate durable state and its flag scan checked "
            f"NOTHING ({tracked.stderr.strip()[-200:]}). It is scoped to tracked files on purpose — an "
            "untracked build tree is not something this repository states."
        )
    for rel in sorted(tracked.stdout.split()):
        if rel.startswith(FLOW_PREFIXES):
            continue
        try:
            body = (ROOT / rel).read_text()
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(body.splitlines(), 1):
            if flag.search(line):
                fail(
                    f"{rel}:{number} instructs `-p {prod_project}`, which "
                    f"{PROD_COMPOSE.relative_to(ROOT)} now carries as `name: {prod_project}`. Two statements of "
                    f"one project name drift, and the one a reader obeys is whichever they saw last — while the "
                    f"one the tooling obeys is the file. Drop the flag; the file is what makes that safe."
                )

# ---- 7. the documented verification pins exactly the identity that signs -------------------------------------

CHART = CHART_DIR / "Chart.yaml"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
# The registry the publish pushes to. The owner is not taken from the workflow — there it is
# `github.repository_owner`, which is whoever runs it, a fork included — but from the chart's `home`, the one
# file of state that names the upstream.
REGISTRY = "ghcr.io"
UPSTREAM = re.compile(r"^https://github\.com/([^/\s]+)/([^/\s]+?)/?$")
FENCE = re.compile(r"^```[^\n]*\n(.*?)^```", re.MULTILINE | re.DOTALL)
# The operator's tool runs the same verification (ADR 0096): the identity it hands cosign is two module-level string
# constants, read here without importing it.
VEYQCTL = repo_layout.VEYQCTL
VEYQCTL_ISSUER = "COSIGN_OIDC_ISSUER"
VEYQCTL_IDENTITY = "COSIGN_IDENTITY_REGEXP"


def veyqctl_identity() -> tuple[str | None, str | None]:
    """`veyqctl`'s pinned issuer and identity pattern, or None for one it does not assign as a string literal."""
    if not VEYQCTL.is_file():
        return None, None
    found: dict[str, str] = {}
    for node in ast.parse(VEYQCTL.read_text()).body:
        if (isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str)):
            found[node.targets[0].id] = node.value.value
    return found.get(VEYQCTL_ISSUER), found.get(VEYQCTL_IDENTITY)


def verify_commands(markdown: str) -> list[list[str]]:
    """Every documented signature verification, as argv from the subcommand on, from the fenced blocks of a markdown
    file: `cosign verify` (an image, the chart) and `cosign verify-blob` (`veyqctl` itself, ADR 0096 decision 3)."""
    commands = []
    for block in FENCE.findall(markdown):
        for line in block.replace("\\\n", " ").splitlines():
            line = line.split(" #", 1)[0].strip()
            if not line.startswith("cosign "):
                continue
            try:
                argv = shlex.split(line)
            except ValueError as e:
                fail(f"{VERIFY_PAGE.relative_to(ROOT)}: cannot parse the command `{line[:80]}…`: {e}")
                continue
            if argv[:2] in (["cosign", "verify"], ["cosign", "verify-blob"]):
                commands.append(argv[1:])
    return commands


verify_summary = ""
upstream = None
if CHART.is_file():
    upstream = UPSTREAM.match(str((yaml.safe_load(CHART.read_text()) or {}).get("home") or ""))
if upstream is None:
    fail(
        f"{CHART.relative_to(ROOT)} has no `home: https://github.com/<owner>/<repo>` — it is where this guard "
        f"reads the upstream a verification must pin, so without it the identity check below checks NOTHING"
    )
elif not VERIFY_PAGE.is_file():
    fail(f"{VERIFY_PAGE.relative_to(ROOT)} is missing — it is where an operator is told how to verify a release")
else:
    owner, repo = upstream.group(1), upstream.group(2)
    # The identity a verification pins is the RELEASE workflow's — the promotion (ADR 0091 decision 5) — never the
    # pipeline's, which signs every snapshot: a snapshot and its release are one digest, so the identity is the only
    # thing that tells them apart.
    workflow_file = RELEASE_WORKFLOW
    snapshot_file = WORKFLOW.relative_to(ROOT).as_posix()

    def san(ref: str, workflow: str = workflow_file, slug: str = f"{owner}/{repo}") -> str:
        """The certificate identity keyless signing from a GitHub workflow produces."""
        return f"https://github.com/{slug}/{workflow}@{ref}"

    # The branches a release is promoted from are the ones a snapshot is published from (assertion 2's scenarios).
    accepted = {f"a release promoted on `{b}`": san(f"refs/heads/{b}") for b in publishing_branches}
    rejected = {
        **{
            f"`{w.relative_to(ROOT).as_posix()}` (another workflow) on main": san(
                "refs/heads/main", workflow=w.relative_to(ROOT).as_posix()
            )
            for w in sorted(WORKFLOW.parent.glob("*.y*ml"))
            if w.relative_to(ROOT).as_posix() != RELEASE_WORKFLOW
        },
        # Named apart, whether or not the file glob above still finds it: the snapshot identity, on every branch a
        # snapshot is signed on. Accepting it would make every snapshot verify as a release.
        **{f"the snapshot identity (`{snapshot_file}`) on `{b}`": san(f"refs/heads/{b}", workflow=snapshot_file)
           for b in publishing_branches},
        **{f"a push to `{b}`, not a release branch": san(f"refs/heads/{b}") for b in
           non_release_branches(version_match.group(1) if version_match else "1")},
        "a pull request ref": san("refs/pull/1/merge"),
        "a tag ref": san(f"refs/tags/v{version_value}.0"),
        # A `release` event runs on the tag of the release it was saved on — here a snapshot's: not a branch a
        # release is promoted from.
        "a snapshot tag ref": san(f"refs/tags/snapshot-{version_value}.0"),
        "another owner's repository": san("refs/heads/main", slug=f"not-{owner}/{repo}"),
        "an owner whose name only starts with ours": san("refs/heads/main", slug=f"{owner}x/{repo}"),
        f"a repository whose name only starts with `{repo}`": san("refs/heads/main", slug=f"{owner}/{repo}-fork"),
        "the right identity quoted inside another URL": "https://evil.example/" + san("refs/heads/main"),
    }
    if not accepted:
        fail(
            "assertion 2's scenarios found no branch the publish runs from, so the verify identity has nothing "
            "to be checked against and this assertion would check NOTHING"
        )

    commands = verify_commands(VERIFY_PAGE.read_text())
    if not commands:
        fail(
            f"{VERIFY_PAGE.relative_to(ROOT)} documents no `cosign verify`. Both images are keyless-signed by the "
            f"publish, and a signature nobody is told to check protects nobody (ADR 0057 §3). Document one per "
            f"published image, pinned to `{workflow_file}` in {owner}/{repo} on its release branches."
        )
    verified_images: set[str] = set()
    # `veyqctl verify` runs the same check for an operator (ADR 0096): its pinned pair is one more verification held
    # to the same accepts and rejects, and to the page's own identity, so the two can never state different ones.
    tool_issuer, tool_identity = veyqctl_identity()
    page_identities = {p for argv in commands for p in flag_values(argv, "--certificate-identity-regexp")}
    if tool_identity is None or tool_issuer is None:
        fail(
            f"{VEYQCTL.relative_to(ROOT)} assigns no `{VEYQCTL_ISSUER}` / `{VEYQCTL_IDENTITY}` string constant — "
            f"they are where this guard reads the identity `veyqctl verify` hands cosign, so it checked NOTHING there"
        )
    elif page_identities and page_identities != {tool_identity}:
        fail(
            f"{VEYQCTL.relative_to(ROOT)} verifies against `{tool_identity}`, and {VERIFY_PAGE.relative_to(ROOT)} "
            f"documents {sorted(page_identities)} — the operator's tool and the operator's page state one identity, "
            f"or one of them accepts what the other refuses"
        )
    tool_argv = ["verify"] + ([] if tool_issuer is None else ["--certificate-oidc-issuer", tool_issuer]) + (
        [] if tool_identity is None else ["--certificate-identity-regexp", tool_identity])
    sources = [(VERIFY_PAGE.relative_to(ROOT), argv) for argv in commands]
    if tool_identity is not None and tool_issuer is not None:
        sources.append((VEYQCTL.relative_to(ROOT), tool_argv))
    verify_summary = (
        f", and {len(commands)} documented `cosign verify`/`verify-blob` command(s) and `veyqctl`'s each accept the "
        f"{len(accepted)} release identities (`release.yml` on each publishing branch) and reject {len(rejected)} others"
    )
    for where, argv in sources:
        shown = " ".join(argv)[:120]
        issuers = flag_values(argv, "--certificate-oidc-issuer")
        if flag_values(argv, "--certificate-oidc-issuer-regexp") or issuers != [OIDC_ISSUER]:
            fail(
                f"{where}: `cosign {shown}` must pin exactly "
                f"`--certificate-oidc-issuer {OIDC_ISSUER}` — GitHub's OIDC issuer is what makes the identity "
                f"below mean `a GitHub workflow run`, and any other issuer lets someone else mint that string"
            )
        exact = flag_values(argv, "--certificate-identity")
        patterns = flag_values(argv, "--certificate-identity-regexp")
        if len(exact) + len(patterns) != 1:
            fail(
                f"{where}: `cosign {shown}` must carry exactly one certificate "
                f"identity (found {len(exact) + len(patterns)})"
            )
            continue
        if exact:
            matches = lambda subject, want=exact[0]: subject == want  # noqa: E731
        else:
            try:
                # cosign matches with Go's `regexp.MatchString`, which is UNANCHORED — `re.search`, not
                # `re.fullmatch`, is the faithful model. A pattern without `^…$` is exactly what it lets through.
                compiled = re.compile(patterns[0])
            except re.error as e:
                fail(f"{where}: the identity pattern `{patterns[0]}` does not compile: {e}")
                continue
            matches = lambda subject, rx=compiled: rx.search(subject) is not None  # noqa: E731
        for what, subject in accepted.items():
            if not matches(subject):
                fail(
                    f"{where}: `cosign {shown}` rejects {what} ({subject}) — the "
                    f"release is promoted and signed on that branch, so a genuine release fails to verify"
                )
        for what, subject in rejected.items():
            if matches(subject):
                fail(
                    f"{where}: `cosign {shown}` accepts {what} ({subject}). A "
                    f"verification is only as narrow as the identity it pins (ADR 0057 §3) — this one "
                    f"accepts a signature the veyq release never made."
                )
        for token in argv:
            # One segment or more: the chart lives at `charts/veyq`, and a token this did not collect would be one
            # nobody held to a digest.
            match = re.match(rf"^{re.escape(REGISTRY)}/{re.escape(owner)}/((?:[a-z0-9-]+/)*[a-z0-9-]+)([@:])", token)
            if match:
                if match.group(2) == ":":
                    fail(
                        f"{where}: `cosign {shown}` verifies a TAG. A tag can move "
                        f"between the verify and the pull; verify the digest the tag resolved to, and deploy "
                        f"that digest (ADR 0057 §3)."
                    )
                else:
                    verified_images.add(match.group(1))
    for image in sorted(published_images(WORKFLOW.read_text()) if WORKFLOW.is_file() else set()):
        if commands and image not in verified_images:
            fail(
                f"{VERIFY_PAGE.relative_to(ROOT)} never verifies `{REGISTRY}/{owner}/{image}` by digest. The publish "
                f"signs every image it ships, and an operator told to verify one of a pair runs the other on "
                f"trust — and the pair includes the higher-privilege admin image (ADR 0065)."
            )
    if commands and not any(argv[0] == "verify-blob" and "veyqctl" in argv[1:] for argv in commands):
        fail(
            f"{VERIFY_PAGE.relative_to(ROOT)} never runs `cosign verify-blob` on `veyqctl`. The publish signs it with "
            f"`cosign sign-blob` (ADR 0096 decision 3) because an operator fetches it by hand before anything else "
            f"is checked — a signature nobody is told to check protects nobody, and that `veyqctl` is what checks "
            f"every image after it."
        )
    if commands and not any(argv[0] == "verify-blob" and release_assets.MANIFEST in argv[1:]
                            and flag_values(argv, "--bundle") == [release_assets.MANIFEST_BUNDLE] for argv in commands):
        fail(
            f"{VERIFY_PAGE.relative_to(ROOT)} never runs `cosign verify-blob --bundle {release_assets.MANIFEST_BUNDLE}` "
            f"on `{release_assets.MANIFEST}`. The publish signs the manifest, and `veyqctl` verifies it before it trusts "
            f"any sha256 or digest in it (inbox #469) — the page keeps every step `veyqctl` runs, so that one can be done "
            f"by hand, and a by-hand check that reads the manifest's digests unverified trusts its source."
        )
    chart_ref = f"charts/{(yaml.safe_load(CHART.read_text()) or {}).get('name') or ''}"
    if commands and chart_ref not in verified_images:
        fail(
            f"{VERIFY_PAGE.relative_to(ROOT)} never verifies `{REGISTRY}/{owner}/{chart_ref}` by digest. The publish "
            f"signs the chart beside the images (ADR 0064), and a chart nobody is told to verify is the one "
            f"artifact of the release an operator installs on trust — the one that chooses which images run."
        )
    values_file = CHART_DIR / "values.yaml"
    values = (yaml.safe_load(values_file.read_text()) or {}) if values_file.is_file() else {}
    chart_name = str((yaml.safe_load(CHART.read_text()) or {}).get("name") or "")
    for key, image in (("image", chart_name), ("admin.image", f"{chart_name}-admin"),
                       ("backup.image", f"{chart_name}-tools")):
        node = values
        for part in key.split("."):
            node = (node or {}).get(part) if isinstance(node, dict) else None
        default = (node or {}).get("repository") if isinstance(node, dict) else None
        want = f"{REGISTRY}/{owner}/{image}"
        if default != want:
            fail(
                f"{values_file.relative_to(ROOT)}: `{key}.repository` defaults to `{default}`, not `{want}` — "
                f"the chart installs from the registry with the upstream's own images, the ones "
                f"{VERIFY_PAGE.relative_to(ROOT)} verifies (ADR 0064); a fork overrides it knowingly"
            )
        elif commands and image not in verified_images:
            fail(
                f"{values_file.relative_to(ROOT)}: `{key}.repository` defaults to `{want}`, which "
                f"{VERIFY_PAGE.relative_to(ROOT)} never verifies by digest — a default nobody is told to verify"
            )
    if WORKFLOW.is_file() and "certificate-identity" in WORKFLOW.read_text():
        fail(
            f"{snapshot_file} states a verification identity of its own. The identity is stated once, in "
            f"{VERIFY_PAGE.relative_to(ROOT)}, where this guard holds it to what signs — a second copy is one "
            f"nothing checks, and the one the workflow carried accepted any workflow on any ref."
        )

# ---- 14. the promotion: one workflow, started by hand, each job holding only what it writes, publishing last ----------
# ADR 0091 decision 5 (and decision 3 of ADR 0096, which assertion 11 used to hold in `ci.yml`): a release is the
# snapshot's bytes under the release's names and signature, started from a saved draft and a dispatch (owner
# 2026-11-06). The file is the release identity (assertion 7), so what it may do is held here, job by job.
release_summary = ""
RELEASE_FILE = ROOT / RELEASE_WORKFLOW
# Each job's permissions, exactly: a job added, or a permission widened, is a decision this guard makes someone take.
RELEASE_JOBS = {
    "check": {"contents": "write", "packages": "read"},
    "promote": {"contents": "read", "packages": "write", "id-token": "write"},
    "publish": {"contents": "write"},
    "channels": {"contents": "read", "packages": "write"},
    "refused": {"contents": "write"},
}
BUILD_RUN = re.compile(r"\b(?:docker\s+build|buildx\s+build|gradlew|pnpm\s|npm\s|quarkusBuild)\b")
PUBLISH_DRAFT = re.compile(r"-F\s+draft=false\b")
RELEASE_WRITE = re.compile(r"\bgh\s+release\s+(?:create|upload|edit|delete)\b|\bgh\s+api\s+-X\s+(?:PATCH|POST|DELETE)\b[^\n]*releases"
                           r"|uploads\.github\.com")
if not RELEASE_FILE.is_file():
    fail(f"{RELEASE_WORKFLOW} is missing — the release identity (assertion 7) names a workflow that does not exist, so "
         f"no release can be promoted and none would ever verify")
else:
    try:
        rdoc = yaml.safe_load(RELEASE_FILE.read_text()) or {}
    except yaml.YAMLError as e:
        rdoc = {}
        fail(f"{RELEASE_WORKFLOW} is not readable YAML: {e}")
    rtext = RELEASE_FILE.read_text()
    rjobs = rdoc.get("jobs") or {}
    rtriggers = rdoc.get("on", rdoc.get(True)) or {}
    if set(rtriggers) != {"workflow_dispatch"}:
        fail(f"{RELEASE_WORKFLOW} triggers on {sorted(map(str, rtriggers))}, not on `workflow_dispatch` alone — a release "
             f"is started by its owner (a saved draft, then Run workflow, ADR 0091 decision 5); a push, a pull request, "
             f"a tag or a schedule would promote what nobody chose")
    inputs = ((rtriggers.get("workflow_dispatch") or {}) if isinstance(rtriggers.get("workflow_dispatch"), dict)
              else {}).get("inputs") or {}
    unscheduled = inputs.get("unscheduled_major") or {}
    if set(inputs) != {"unscheduled_major"} or unscheduled.get("type") != "boolean" or unscheduled.get("default") is not False:
        fail(f"{RELEASE_WORKFLOW}'s dispatch takes {sorted(inputs)} — exactly the boolean `unscheduled_major`, default "
             f"false: the draft names what is released, the dispatch only starts it")
    concurrency = rdoc.get("concurrency") or {}
    if concurrency.get("group") != "veyq-release" or concurrency.get("cancel-in-progress") is not False:
        fail(f"{RELEASE_WORKFLOW}'s concurrency is {concurrency} — group `veyq-release`, never cancelled: two promotions "
             f"(or a promotion and the snapshot retention) must not interleave, and a cancelled one leaves names half-moved")
    if rdoc.get("permissions") != {}:
        fail(f"{RELEASE_WORKFLOW} grants {rdoc.get('permissions')} to every job — `permissions: {{}}`, each job its own")
    if set(rjobs) != set(RELEASE_JOBS):
        fail(f"{RELEASE_WORKFLOW} has the jobs {sorted(rjobs)}, this guard holds {sorted(RELEASE_JOBS)} — a job added or "
             f"renamed is a permission decision; hold it here")
    for name, want in RELEASE_JOBS.items():
        got = (rjobs.get(name) or {}).get("permissions")
        if name in rjobs and got != want:
            fail(f"{RELEASE_WORKFLOW}: job `{name}` holds {got}, not exactly {want} — each job holds only what it writes "
                 f"(signing and the registry in `promote`, the GitHub Release in `publish`, the channels after it)")

    def rsteps(job: str) -> list[dict]:
        return [s for s in ((rjobs.get(job) or {}).get("steps") or []) if isinstance(s, dict)]

    def rneeds(job: str) -> set[str]:
        raw = (rjobs.get(job) or {}).get("needs") or []
        return {raw} if isinstance(raw, str) else {str(n) for n in raw}

    for job, needed in (("promote", {"check"}), ("publish", {"check", "promote"}), ("channels", {"check", "publish"}),
                        ("refused", {"check", "promote", "publish"})):
        if not needed <= rneeds(job):
            fail(f"{RELEASE_WORKFLOW}: job `{job}` does not need {sorted(needed - rneeds(job))} — the order is check, "
                 f"promote, publish, then the channels; `refused` reads all three")
    channels_if = " ".join(str((rjobs.get("channels") or {}).get("if") or "").split())
    if "needs.publish.outputs.published == 'true'" not in channels_if or "||" in channels_if:
        fail(f"{RELEASE_WORKFLOW}: `channels` runs on `{channels_if}` — `<major>` and `latest` move only once the release "
             f"is published, or they point at a release that does not exist")
    refused_if = " ".join(str((rjobs.get("refused") or {}).get("if") or "").split())
    if "needs.publish.outputs.published != 'true'" not in refused_if:
        fail(f"{RELEASE_WORKFLOW}: `refused` runs on `{refused_if}` — it puts a draft back, so never after publication")

    for job in rjobs:
        for step in rsteps(job):
            run = str(step.get("run") or "")
            uses = str(step.get("uses") or "").split("@", 1)[0]
            if uses == "docker/build-push-action" or BUILD_RUN.search(run):
                fail(f"{RELEASE_WORKFLOW}: job `{job}`, step `{step.get('name')}` builds — a release is the snapshot's "
                     f"bytes, never built again (ADR 0091 decision 5)")
            if job not in ("publish", "refused") and (RELEASE_WRITE.search(run) or GIT_PUSH.search(run)):
                fail(f"{RELEASE_WORKFLOW}: job `{job}`, step `{step.get('name')}` writes to the repository or a GitHub "
                     f"Release — only `publish` (and `refused`, putting a draft back) does")
            if job != "channels" and re.search(r"\blatest\b|\$\{?MAJOR\b", run):
                fail(f"{RELEASE_WORKFLOW}: job `{job}`, step `{step.get('name')}` names `latest` or `<major>` — the "
                     f"channels move in `channels` alone, after publication")
    if "@refs/heads" in rtext or "@refs/tags" in rtext:
        fail(f"{RELEASE_WORKFLOW} states a certificate identity of its own — the snapshot identity is "
             f"scripts/promote.py's, the release identity `veyqctl`'s, each held where it is written")
    if "scripts/promote.py check" not in "\n".join(str(s.get("run") or "") for s in rsteps("check")):
        fail(f"{RELEASE_WORKFLOW}: `check` does not run `scripts/promote.py check` — nothing decides the promotion")

    # The publication: one step, after every file is attached and the git tag set; after it only clean-up.
    publish_steps = rsteps("publish")
    at = {k: [i for i, s in enumerate(publish_steps) if pattern.search(str(s.get("run") or ""))] for k, pattern in (
        ("publish", PUBLISH_DRAFT), ("upload", re.compile(r"\bgh\s+release\s+upload\b")),
        ("verify", re.compile(r"\bcosign\s+verify-blob\b")), ("tag", re.compile(r"\bgh\s+api\s+--method\s+POST\s+\"repos/\$\{GITHUB_REPOSITORY\}/git/refs\"\s*\\?\s*--raw-field\s+\"ref=refs/tags/v\$\{VERSION\}\"")),
        ("move", re.compile(r"tag_name=\"v\$\{VERSION\}\"")))}
    if len(at["publish"]) != 1:
        fail(f"{RELEASE_WORKFLOW}: `publish` publishes the draft in {len(at['publish'])} step(s), not one")
    else:
        last = at["publish"][0]
        for key in ("upload", "verify", "tag", "move"):
            if not at[key] or max(at[key]) > last:
                fail(f"{RELEASE_WORKFLOW}: `publish` {'never' if not at[key] else 'only after publishing'} "
                     f"{dict(upload='attaches the files', verify='verifies the files as veyqctl will', tag='sets the git tag', move='moves the draft onto it')[key]} "
                     f"— an immutable release takes nothing once published (ADR 0096 decision 3), so publication is LAST")
        for step in publish_steps[last + 1:]:
            run = str(step.get("run") or "")
            for line in run.splitlines():
                if (RELEASE_WRITE.search(line) and "-X DELETE" not in line) or (
                        GIT_PUSH.search(line) and "--delete \"refs/tags/snapshot-" not in line):
                    fail(f"{RELEASE_WORKFLOW}: `publish` writes after publishing (`{line.strip()[:80]}`) — only the "
                         f"snapshot's tag and a replaced draft are removed then")
    verify_runs = "\n".join(str(s.get("run") or "") for s in publish_steps)
    if "scripts/promote.py release-identity" not in verify_runs:
        fail(f"{RELEASE_WORKFLOW}: `publish` does not verify the files against `veyqctl`'s own identity "
             f"(`scripts/promote.py release-identity`) — a release `veyqctl` refuses could be published")

    # The files: the snapshot commit's producer, held to the plan, then the two reserved bundles — nothing after.
    files_run = next((str(s.get("run") or "") for s in rsteps("promote") if "release_assets.py" in str(s.get("run"))), "")
    lines = [line.strip() for line in files_run.replace("\\\n", " ").splitlines()]
    producer = [i for i, line in enumerate(lines) if "/snapshot/scripts/release_assets.py" in line]
    checked = [i for i, line in enumerate(lines) if "scripts/promote.py assets" in line]
    blobs = [(i, line) for i, line in enumerate(lines) if re.search(r"\bcosign\s+sign-blob\b", line)]
    bundles = sorted(re.search(r"--bundle\s+\"?[^\s\"]*/([^/\s\"]+)\"?", line).group(1) for _, line in blobs
                     if re.search(r"--bundle\s", line))
    if not producer:
        fail(f"{RELEASE_WORKFLOW}: `promote` does not produce the files with the snapshot commit's own "
             f"`scripts/release_assets.py` (from the `snapshot` worktree) — the files would be this branch's, untested")
    elif not checked or checked[0] < producer[0]:
        fail(f"{RELEASE_WORKFLOW}: `promote` does not hold the produced files to the plan (`scripts/promote.py assets`) "
             f"after producing them — the asset set the lanes installed would go unchecked")
    elif bundles != sorted([release_assets.VEYQCTL_BUNDLE, release_assets.MANIFEST_BUNDLE]) or any(
            i < checked[0] for i, _ in blobs) or any(i > max(i2 for i2, _ in blobs) for i in producer + checked):
        fail(f"{RELEASE_WORKFLOW}: `promote` signs {bundles} — exactly `veyqctl` and the manifest, into the bundle names "
             f"the producer reserves, after the files are checked and with nothing produced after")

    # The chart, from the snapshot's commit, as the release.
    chart_run = next((str(s.get("run") or "") for s in rsteps("promote") if "helm package" in str(s.get("run"))), "")
    if ('/snapshot/infra/kubernetes/charts/' not in chart_run or '--version "$VERSION"' not in chart_run
            or '--app-version "$VERSION"' not in chart_run or "helm push" not in chart_run):
        fail(f"{RELEASE_WORKFLOW}: `promote` does not package the chart from the snapshot's commit as `--version` and "
             f"`--app-version` `$VERSION` and push it (ADR 0064)")
    if not any(re.search(r"\bcosign\s+sign\s", str(s.get("run") or "")) and "charts/veyq" in str(s.get("env") or {})
               for s in rsteps("promote")):
        fail(f"{RELEASE_WORKFLOW}: `promote` does not sign the chart")

    # The pins it shares with `ci.yml`: one cosign, one Helm.
    ci_installer = [s for job in (workflow_doc.get("jobs") or {}) for s in job_steps(workflow_doc, job)
                    if str(s.get("uses") or "").startswith(COSIGN_INSTALLER)]
    ci_cosign = str(((ci_installer[0].get("with") or {}) if ci_installer else {}).get("cosign-release") or "")
    for job in rjobs:
        steps = rsteps(job)
        installs = [i for i, s in enumerate(steps) if str(s.get("uses") or "").startswith(COSIGN_INSTALLER)]
        runs = [i for i, s in enumerate(steps) if COSIGN_RUN.search(str(s.get("run") or "").replace("\\\n", " "))
                or "promote.py check" in str(s.get("run") or "")]
        if runs and (not installs or min(installs) > min(runs)):
            fail(f"{RELEASE_WORKFLOW}: job `{job}` runs cosign before installing the pinned one")
        for i in installs:
            if str((steps[i].get("with") or {}).get("cosign-release")) != ci_cosign:
                fail(f"{RELEASE_WORKFLOW}: job `{job}` installs cosign {(steps[i].get('with') or {}).get('cosign-release')}, "
                     f"`ci.yml` signs with {ci_cosign} — one cosign signs veyq (inbox #474)")
    if (rdoc.get("env") or {}).get("HELM_SHA256") != (workflow_doc.get("env") or {}).get("HELM_SHA256") or \
            (rdoc.get("env") or {}).get("HELM_VERSION") != (workflow_doc.get("env") or {}).get("HELM_VERSION"):
        fail(f"{RELEASE_WORKFLOW}'s Helm pin is not `ci.yml`'s — the chart a release packages is the snapshot's Helm's")
    release_summary = (f"; {RELEASE_WORKFLOW} is started by hand only, in group `veyq-release`, its {len(RELEASE_JOBS)} "
                       f"jobs each holding only what they write, builds nothing, moves the channels after publishing "
                       f"and publishes last")

# Only the release identity's file writes a GitHub Release (assertion 7: what it signs is what verifies).
for other in sorted(WORKFLOW.parent.glob("*.y*ml")):
    if other.relative_to(ROOT).as_posix() == RELEASE_WORKFLOW:
        continue
    for number, line in enumerate(other.read_text().splitlines(), 1):
        if RELEASE_WRITE.search(line) or GH_RELEASE_CREATE.search(line):
            fail(f"{other.relative_to(ROOT)}:{number} writes a GitHub Release — only {RELEASE_WORKFLOW}, the release "
                 f"identity, does")

# ---- 15. a job that can write installs no Python package it has not pinned by hash ---------------------------------
# Code PyPI serves at install time runs with the job's token: in `release.yml`'s `promote` the release identity's OIDC
# token, in `check` the drafts, in `ci.yml`'s `build` the registry. So every `pip install` of every `release.yml` job,
# and of every `ci.yml` job that holds a write permission (`id-token`, `packages`, `contents` …), installs exact
# versions by hash and wheels only, from a requirements file whose every requirement carries its hashes. A read-only
# job of `ci.yml` is out of scope: what it could reach is the runner it runs on.
PIP_INSTALL = re.compile(r"\bpip3?\"?\s+install\b|\bpython3?\s+-m\s+pip\s+install\b")
HASHED_REQUIREMENT = re.compile(r"^[A-Za-z0-9_.-]+==[0-9][^\s]*(?:\s+--hash=sha256:[0-9a-f]{64})+$")
pip_summary = ""


def can_write(permissions: object) -> bool:
    if isinstance(permissions, str):
        return permissions == "write-all"
    return isinstance(permissions, dict) and any(str(v) == "write" for v in permissions.values())


held_installs = 0
requirement_files: set[str] = set()
for wf_path, every_job in ((WORKFLOW, False), (ROOT / RELEASE_WORKFLOW, True)):
    if not wf_path.is_file():
        continue
    wf_doc = yaml.safe_load(wf_path.read_text()) or {}
    default_permissions = wf_doc.get("permissions")
    for job_name, job in (wf_doc.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        permissions = job.get("permissions", default_permissions)
        if not (every_job or can_write(permissions)):
            continue
        for step in job.get("steps") or []:
            if not isinstance(step, dict):
                continue
            for line in str(step.get("run") or "").replace("\\\n", " ").splitlines():
                if not PIP_INSTALL.search(line):
                    continue
                held_installs += 1
                files = re.findall(r"(?:^|\s)(?:-r|--requirement)\s+(\S+)", line)
                if "--require-hashes" not in line or "--only-binary :all:" not in line or not files:
                    fail(
                        f"{wf_path.relative_to(ROOT)}: job `{job_name}` (permissions {permissions}) runs "
                        f"`{line.strip()[:100]}` — a job that can write installs only exact, hashed wheels: "
                        f"`pip install --require-hashes --only-binary :all: -r <requirements>`, or what PyPI serves at "
                        f"that moment runs with its token"
                    )
                requirement_files.update(files)
for rel in sorted(requirement_files):
    path = ROOT / rel
    if not path.is_file():
        fail(f"`{rel}`, which a writing job installs from, does not exist")
        continue
    requirements = [" ".join(line.split()) for line in path.read_text().replace("\\\n", " ").splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
    if not requirements:
        fail(f"`{rel}` names no requirement, so the hashed install proves nothing")
    for requirement in requirements:
        if not HASHED_REQUIREMENT.match(requirement):
            fail(f"`{rel}`: `{requirement[:80]}` is not `<name>==<version>` followed by its `--hash=sha256:` lines — "
                 f"every requirement of a writing job is exact and hashed")
if held_installs == 0:
    fail("no `pip install` found in release.yml or in a writing job of ci.yml — assertion 15 checked NOTHING; re-anchor "
         "it if the jobs no longer install Python packages")
pip_summary = (f"; every one of the {held_installs} `pip install`(s) of a job that can write takes exact, hashed wheels "
               f"({', '.join(sorted(requirement_files))})")

# ---- 16. no job relies on the implicit `success()` behind a job that can be skipped --------------------------------
# GitHub adds `success()` to a job's `if:` unless the condition calls a status function itself, and that `success()`
# looks at the WHOLE chain of needs: a job skipped anywhere upstream skips everything after it. `snapshot` waits on the
# switched-off `upgrade-proof` (`if: false`) and survives through `always()`; on the first real run (2026-11-06,
# 42274582708) `snapshot-tag`, which needs `snapshot` and stated no status function, was skipped — the snapshot got no
# tag. So in `ci.yml` and `release.yml` a job whose needs reach a job with an `if:` (one that can be skipped) states its
# own gate: a status function (`always()`, `!cancelled()`) and, for each direct need that can be skipped or follows
# one, that need's result (`needs.<job>.result == 'success'`, or a `contains(fromJSON(…), needs.<job>.result)`).
STATUS_FUNCTION = re.compile(r"\b(?:always|cancelled|failure|success)\s*\(\s*\)")
gates_checked = 0
for wf_path in (WORKFLOW, ROOT / RELEASE_WORKFLOW):
    if not wf_path.is_file():
        continue
    wf_jobs = (yaml.safe_load(wf_path.read_text()) or {}).get("jobs") or {}

    def direct_needs(name: str) -> list[str]:
        raw = (wf_jobs.get(name) or {}).get("needs") or []
        return [raw] if isinstance(raw, str) else [str(n) for n in raw]

    skippable: dict[str, bool] = {}

    def can_be_skipped(name: str, seen: frozenset = frozenset()) -> bool:
        """A job with an `if:` can be skipped, and so, by GitHub's implicit `success()`, can anything after one."""
        if name in skippable:
            return skippable[name]
        job = wf_jobs.get(name) or {}
        result = "if" in job or any(can_be_skipped(n, seen | {name}) for n in direct_needs(name) if n not in seen)
        skippable[name] = result
        return result

    for name, job in wf_jobs.items():
        if not isinstance(job, dict):
            continue
        risky = [n for n in direct_needs(name) if can_be_skipped(n)]
        if not risky:
            continue
        gates_checked += 1
        condition = " ".join(str(job.get("if") or "").split())
        if condition.startswith("${{") and condition.endswith("}}"):
            condition = condition[3:-2].strip()
        terms = {t.strip() for t in condition.split("&&")}
        if not STATUS_FUNCTION.search(condition) or "success()" in condition.replace(" ", ""):
            fail(
                f"{wf_path.relative_to(ROOT)}: job `{name}` needs {risky}, which can be skipped (or follow a job that "
                f"can), and its `if:` (`{condition or 'none'}`) states no status function of its own — GitHub's implicit "
                f"`success()` then skips it whenever anything upstream was skipped, as it skipped `snapshot-tag` on the "
                f"first real run. Gate it with `!cancelled()` (or `always()`) and the needs' results."
            )
            continue
        for need in risky:
            if (f"needs.{need}.result == 'success'" not in terms
                    and not any(f"needs.{need}.result)" in t and t.startswith("contains(fromJSON(") for t in terms)):
                fail(
                    f"{wf_path.relative_to(ROOT)}: job `{name}` lifts the implicit `success()` (`{condition}`) but does "
                    f"not require `needs.{need}.result == 'success'` as one of its `&&` terms — it would run after a "
                    f"failed or skipped `{need}`"
                )
if gates_checked == 0:
    fail("no job in ci.yml or release.yml follows a job that can be skipped — assertion 16 checked NOTHING; re-anchor it")


# ---- 17. only the weekly schedule and a dispatch publish, and never twice for one commit ---------------------------
# Owner 2026-11-07: a snapshot per green push flooded the registry and the tags once Dependabot opened its pull
# requests. A push still runs the whole pipeline (ADR 0091 decision 2); what publishes is the weekly schedule and a
# dispatch, on a HEAD that has no snapshot yet.
EXPR = re.compile(r"\$\{\{(.*?)\}\}", re.DOTALL)
EXPR_TOKEN = re.compile(r"\s*(?:('[^']*')|(github\.event_name|github\.ref)\b|(format)\s*(?=\()|(==|!=|&&|\|\||!|\(|\)|,))")


def evaluate_expression(text: str, context: dict[str, str]) -> str:
    """A GitHub expression of the small subset a concurrency group needs, evaluated; anything else is refused.

    Literals, `github.event_name`, `github.ref`, `format(…)`, `==`, `!=`, `&&`, `||`, `!` and parentheses. `&&` and `||`
    return an operand, as GitHub's do. String comparison is GitHub's: case-insensitive.
    """
    tokens: list[tuple[str, str]] = []
    pos, text = 0, text.strip()
    while pos < len(text):
        m = EXPR_TOKEN.match(text, pos)
        if not m or m.end() == pos:
            raise ValueError(f"cannot read `{text[pos:pos + 30]}` (this guard models only a concurrency group's subset)")
        literal, name, func, op = m.groups()
        tokens.append(("lit", literal[1:-1]) if literal else ("ctx", name) if name else ("fn", func) if func
                      else ("op", op))
        pos = m.end()
    at = 0

    def peek() -> tuple[str, str] | None:
        return tokens[at] if at < len(tokens) else None

    def take(kind: str, value: str | None = None) -> tuple[str, str]:
        nonlocal at
        token = peek()
        if token is None or token[0] != kind or (value is not None and token[1] != value):
            raise ValueError(f"expected {value or kind}, found {token}")
        at += 1
        return token

    def primary() -> object:
        token = peek()
        if token is None:
            raise ValueError("the expression ends early")
        if token == ("op", "!"):
            take("op", "!")
            return not primary()
        if token == ("op", "("):
            take("op", "(")
            value = disjunction()
            take("op", ")")
            return value
        if token[0] == "lit":
            take("lit")
            return token[1]
        if token[0] == "ctx":
            take("ctx")
            return context[token[1]]
        if token[0] == "fn":
            take("fn")
            take("op", "(")
            args = [disjunction()]
            while peek() == ("op", ","):
                take("op", ",")
                args.append(disjunction())
            take("op", ")")
            out = str(args[0])
            for index, arg in enumerate(args[1:]):
                out = out.replace("{" + str(index) + "}", str(arg))
            return out
        raise ValueError(f"unexpected {token}")

    def comparison() -> object:
        left = primary()
        while peek() in (("op", "=="), ("op", "!=")):
            op = take("op")[1]
            right = primary()
            same = str(left).lower() == str(right).lower()
            left = same if op == "==" else not same
        return left

    def conjunction() -> object:
        left = comparison()
        while peek() == ("op", "&&"):
            take("op", "&&")
            right = comparison()
            left = right if left else left
        return left

    def disjunction() -> object:
        left = conjunction()
        while peek() == ("op", "||"):
            take("op", "||")
            right = conjunction()
            left = left if left else right
        return left

    value = disjunction()
    if at != len(tokens):
        raise ValueError(f"unread tokens after the expression: {tokens[at:]}")
    return str(value)


publish_summary = ""
if workflow_doc and version_match:
    rel = WORKFLOW.relative_to(ROOT)
    triggers = workflow_doc.get("on", workflow_doc.get(True)) or {}
    for name in ("push", "pull_request", "workflow_dispatch"):
        if name not in triggers:
            fail(f"{rel} does not trigger on `{name}` — every push and pull request runs the whole pipeline (ADR 0091 "
                 f"decision 2), and a dispatch is the owner's snapshot now (ADR 0091 decision 5)")
    crons = [str(entry.get("cron")) for entry in (triggers.get("schedule") or [])
             if isinstance(entry, dict) and entry.get("cron")] if isinstance(triggers.get("schedule"), list) else []
    if not crons:
        fail(f"{rel} has no `schedule` with a `cron` — the weekly snapshot (owner 2026-11-07) never runs, so `main` is "
             f"published only when someone remembers to dispatch")
    events = {event for event, _, _ in publishing_runs}
    if events != PUBLISHING_EVENTS:
        fail(f"the `{VERSION_STEP}` step turned {sorted(events) or 'no event'} into a publish, not exactly "
             f"{sorted(PUBLISHING_EVENTS)} — a push runs the whole pipeline and publishes nothing; only the weekly "
             f"schedule and a dispatch do (owner 2026-11-07)")
    if set(tagged_probes) != PUBLISHING_EVENTS:
        fail(f"assertion 2 runs {sorted(set(tagged_probes)) or 'no event'} on a HEAD that has its snapshot, not every "
             f"one of {sorted(PUBLISHING_EVENTS)} — assertion 17's write-once half would check too little")
    for event, checkout, label in publishing_runs:
        if checkout == "tagged":
            fail(f"the `{VERSION_STEP}` step publishes for {label} — that HEAD carries a `snapshot-` tag, and a snapshot "
                 f"is written once and never replaced (ADR 0091 decision 5)")
    if not any(checkout == "full" for _, checkout, _ in publishing_runs):
        fail(f"the `{VERSION_STEP}` step publishes nothing for a HEAD whose only `snapshot-` tag is on its parent — read "
             f"as `published`, an earlier snapshot would stop every later one")

    concurrency = workflow_doc.get("concurrency")
    group = str(concurrency.get("group") or "") if isinstance(concurrency, dict) else str(concurrency or "")
    if not isinstance(concurrency, dict) or concurrency.get("cancel-in-progress") is not False:
        fail(f"{rel}'s concurrency is {concurrency!r} — it needs a `group` and `cancel-in-progress: false`: a publish "
             f"cancelled midway leaves a half-written snapshot behind")
    try:
        major = version_match.group(1)

        def group_of(event: str, ref: str) -> str:
            return EXPR.sub(lambda m: evaluate_expression(m.group(1), {"github.event_name": event, "github.ref": ref}),
                            group)

        refs = ["refs/heads/main", f"refs/heads/release/{major}.x"]
        seen: dict[str, tuple[str, str]] = {}
        for ref in refs:
            schedule, dispatch = group_of("schedule", ref), group_of("workflow_dispatch", ref)
            push, pull = group_of("push", ref), group_of("pull_request", "refs/pull/12/merge")
            if schedule != dispatch:
                fail(f"{rel}: on {ref} the schedule runs in concurrency group `{schedule}` and a dispatch in `{dispatch}` "
                     f"— two publishing runs of one ref must go one after the other, or both build a snapshot of a HEAD "
                     f"neither has tagged yet")
            for other, value in (("a push", push), ("a pull request", pull)):
                if value == schedule:
                    fail(f"{rel}: on {ref} {other} shares concurrency group `{value}` with the publishing runs — "
                         f"queued behind one it delays the snapshot, and a push arriving while a scheduled run waits "
                         f"cancels that run (GitHub keeps one pending run per group)")
            for value, what in ((schedule, "publish"), (push, "push")):
                if value in seen and seen[value] != (what, ref):
                    fail(f"{rel}: concurrency group `{value}` is shared by {seen[value]} and {(what, ref)} — one group "
                         f"per ref, or a push to one branch waits on another's run")
                seen.setdefault(value, (what, ref))
        publish_summary = (f"; only the schedule ({', '.join(crons) or '?'}) and a dispatch publish, never a HEAD "
                           f"that has its snapshot, in a concurrency group of their own")
    except (ValueError, KeyError) as e:
        fail(f"{rel}'s concurrency group `{group}` cannot be evaluated here: {e}")

if not written_tags and not errors:
    # A guard that checked nothing and a guard whose subject is sound report the same green.
    fail(
        f"no literal veyq image tag found anywhere under {'/, '.join(map(repo_layout.rel, TAG_SCAN_DIRS))}/ or "
        f"{RUN_PAGES.relative_to(ROOT)}/ — this half of the guard "
        f"checked NOTHING and would report green either way"
    )

if errors:
    print("RELEASE IDENTITY VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(
    f"release identity OK: VERSION {version_value} is the major, the minor is counted offline from the history "
    f"({len(publishing_branches)} publishing branch(es), a shallow checkout refused, a pull request and a local run "
    f"publishing nothing), every write waits for `{PUBLISHES}`, the stamp is declared by the {stamped_targets} published "
    f"target stage(s) and no other, which label their version and revision from it with no build step overriding a "
    f"label, a snapshot refuses to replace one, is signed by the pinned cosign {signing_cosign or '?'} and attested per "
    f"lane and architecture, gets its `snapshot-` tag from a job holding `contents: write` alone and no GitHub Release, "
    f"the snapshot waits on every one of the "
    f"{len(stage_jobs) if workflow_doc else 0} stages as green, the producer carries "
    f"the {len(recipe_assets)} recipe file(s) the derivation names ({', '.join(recipe_assets)}), {written_tags} written-down image "
    f"tag(s) name a shape the pipeline emits, and the production stack names its own project "
    f"(`name: {prod_project}`) with nothing in durable state instructing the flag it replaced{verify_summary}"
    f"{release_summary}{pip_summary}{publish_summary}"
)
