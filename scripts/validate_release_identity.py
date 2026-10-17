#!/usr/bin/env python3
"""Guard that a veyq release has an identity, that the identity comes from the tree, and that every artifact
naming a release names a shape the pipeline actually emits (ADR 0057).

**What it asserts.** Eleven things, in the order a release happens:

  1. **`VERSION` exists at the repository root and holds the major alone.** With the history it *is* the release
     identity (ADR 0057 §1): the minor is the number of commits since `VERSION` last changed, so a checkout
     answers what it is, with no network call and no registry — and no tree can hold the minor.
  2. **The publish workflow derives the version from the tree, offline.** The `version` job's compute step is
     *executed* here, inside a throwaway git repository this guard builds — `VERSION` changed twice, then a
     known number of commits, plus a shallow clone of it — so the minor is asserted against a known count, never
     against this repository's own history, which moves. Scenarios: a snapshot and a release dispatch on `main`,
     both on `release/<major>.x`, a push and a pull request (kind `none`, the dry run), and — each must FAIL —
     a dispatch of either kind on a branch that disagrees with the tree or is not a release branch, and a shallow
     checkout. Executing it is the only honest way to assert what a publish *would* stamp; reading it would only
     assert what it looks like. The step is also held to declaring no network tool at all (a `git` that talks to
     a remote included), because a version a registry decides is not an identity (ADR 0057 § Rejected), and to
     receiving each input the scenarios set from the expression a run sets it from. `scripts/release_assets.py`
     counts the release a second time, to name the release's assets, and is held to the step's count on the same
     history and to refusing the shallow clone.
  3. **Only a dispatch writes to a registry; a release refuses to replace itself and leaves exactly one git ref.**
     `workflow_dispatch` takes a boolean `release` input defaulting to false. Every registry write in the file —
     found by what a step does (a login or build-push action, or a `run:` that pushes, logs in or signs), never
     listed — is gated on `workflow_dispatch` by its step, its job or its push input, and the `publish` job as a
     whole is. A `gh release` that writes counts: a GitHub Release is a write to the remote. The `publish` job carries a step that fails when an artifact's tag or `v<major>.<minor>` already
     exists, and a step that creates that ref — both run for `kind == release` only, neither with `--force`, and
     both keyed on the *tree-derived* version rather than on anything else. The `build` job stamps the same value
     into the image as `VEYQ_VERSION`, which is what the running instance later reports — and every build-arg it
     stamps is declared by the target stage each matrix entry publishes, and by **no other stage** of that entry's
     Dockerfile (nor before its first `FROM`). The stage that compiles the web client is served to anyone before any
     login, so a stamp it could read is a release on the pre-auth surface (ADR 0057 §4); a stamp the target does not
     declare is one Docker drops without a word. Both found by stage, from the build matrix, never listed.
  9. **The publish waits on every stage** (ADR 0091 decision 2). The workflow is the one pipeline per commit, and
     its image build starts beside the tests instead of after them — which is sound only while nothing it builds is
     published before every stage is green. So every other job of the file is in the `publish` job's `needs:`, found
     by reading the file's jobs, never listed; and when the publish's `if:` calls a status function (`always()`,
     `failure()`, `cancelled()`) — which lets it run past a failed or skipped need — the condition requires
     `needs.<job>.result == 'success'` of every need, the one exception a job that is literally `if: false`, whose
     `skipped` it may accept alongside `success` (the disabled upgrade proof).
 10. **The snapshot waits on lane L1** (ADR 0090 decision 2). Assertion 9 holds the publish to the jobs the file
     has, and cannot see one that is gone; so the job that runs `scripts/run_stack_e2e.py` — found by what it runs,
     never by its name — must exist, need `build`, run on every architecture the build matrix publishes, and be in
     the publish's `needs:` with `needs.<lane>.result == 'success'` among its `&&` terms.
 11. **A release is one immutable GitHub Release, created last, in one call** (ADR 0096 decision 3). Exactly one
     step of the file runs `gh release create`, in the `publish` job, for `kind == release` only, after the step that
     creates the ref and after every `cosign sign` of an image or the chart; it carries `--verify-tag` and neither
     `--draft` nor `--prerelease`; it runs `scripts/release_assets.py` into a directory and uploads that directory's
     `v$VERSION/*` whole, after exactly two `cosign sign-blob` lines, every one of the step collected (never the first
     match alone): one of `v$VERSION/veyqctl` into `--bundle v$VERSION/<release_assets.VEYQCTL_BUNDLE>` and one of
     `v$VERSION/manifest.json` into `--bundle v$VERSION/<release_assets.MANIFEST_BUNDLE>` — so the names the publish
     writes are the producer's reserved ones, which assertion 4 holds `veyqctl` to — both with `--new-bundle-format`,
     both after the producer line and before `gh release create`, and no line between the manifest's signing and
     that upload runs the producer again or names the manifest (the file uploaded must be the file signed); and
     it hands the boolean dispatch input `unscheduled_major` (default false) to the producer as `--unscheduled`, which
     an earlier step of the job also runs the producer with, before the job's first push, so its refusals come before
     anything is published. No step anywhere uploads to, edits or deletes a GitHub Release (`gh release
     upload|edit|delete`, `gh api …/releases`, `uploads.github.com`, an action whose name says `release`): an immutable
     release takes no asset after it is published.
  8. **The chart is the release's third artifact** (ADR 0064). The refuse step names every published image (the
     build matrix) AND `charts/<name>` (`Chart.yaml`'s `name`), so a release with any of the three already out
     is refused; the step that runs `helm package` sets `--version` and `--app-version` from the tree-derived
     version and pushes to `oci://ghcr.io/<owner>/charts`; a `cosign sign` step names `charts/<name>`; and the
     chart's default image repositories are exactly the upstream images the Run area's verification page
     (`site/src/content/docs/run/verify-a-release.md`) verifies by digest — `image.repository` the chart's own
     name, `admin.image.repository` its `-admin` sibling. And that page verifies the chart itself by digest (assertion 7 collects `ghcr.io/<owner>/charts/<name>`
     tokens as it collects the images', and holds them to a digest the same way).
  4. **The release carries every file its recipe reads** (ADR 0096). `scripts/release_assets.py` derives the
     recipe set — the production compose file, `.env.example`, and every file the compose file reads relative to
     itself, in each spelling Compose has for that (`volumes` short and long-form `type: bind`, `env_file`,
     `label_file`, top-level `configs`/`secrets` `file:`) — and refuses what it cannot derive (`${…}` in such a
     path, `../`, a directory in it, a name GitHub would rename, `include:`, `extends`). Here that derivation is run
     on the tree, its refusals are this guard's reds, and the producer's carry step is run into a scratch directory,
     where every derived file must be, byte-identical to the tree (the `.env` template under its asset name,
     `env.example`). Every `*_ASSET` name `scripts/veyqctl.py` fetches must be one a release has — the producer's
     files, the manifest, or one of the two bundle names the producer reserves and the publish writes (assertion 11).
     The derived list is printed on the OK line. Plus: every veyq image
     tag written down under `infra/` or on the Run pages (`site/src/content/docs/run/`, where the operator
     procedures live) matches a tag the pipeline emits.
  5. **Every service in the release recipe runs an image the operator can PULL.** No service in
     `infra/docker-compose.prod.yml` carries a `build:` stanza, and every veyq-namespaced image it names is one
     `.github/workflows/ci.yml` actually publishes — the set is read out of that workflow's build
     matrix, never listed here (§ *a guard derives its anchor set from the code*). A recipe that builds is a
     recipe only `docker compose` can deploy: Compose hides it by building on the operator's host, and
     [ADR 0064](../docs/decisions/0064-kubernetes-helm-deployment-target.md)'s Helm chart cannot, because a
     chart has nothing to build with.
  6. **The production project name is a property of the compose file, not a rule stated in prose.**
     `infra/docker-compose.prod.yml` declares a top-level `name:`, and no file of durable state instructs
     `-p <that name>` / `--project-name <that name>`. Compose derives a project from the compose file's
     *directory* when the file names none, and both compose files sit in `infra/` — so a production procedure
     that depends on an operator remembering a flag is one shell away from addressing the wrong stack. The two
     halves are one assertion, not two: dropping the flag is safe only *because* the file carries what the
     flag used to say, and re-introducing the flag beside the key is how the two start to disagree.
  7. **The operator's documented signature check is exactly as narrow as what signs** (ADR 0057 §3). Every
     `cosign verify` and `cosign verify-blob` (the one for `veyqctl` itself, ADR 0096 decision 3) on
     `site/src/content/docs/run/verify-a-release.md` pins GitHub's OIDC issuer and one certificate identity; that identity
     **accepts** the signing identity of every branch assertion 2's scenarios turn into a publish, and
     **rejects** every other workflow in `.github/workflows/`, non-release branches (which the version step must
     refuse as well), pull-request and tag refs, another owner, a repository whose name only starts with ours,
     and the right identity quoted inside another URL. Every image the build matrix publishes is verified, by
     digest and never by tag, `veyqctl` itself is verified with `cosign verify-blob`, and the workflow states no identity
     of its own. The upstream is the chart's
     `home`, not the workflow's `github.repository_owner`, which is whoever runs it — a fork included. The
     operator's tool runs the same check (`veyqctl verify`, `install`, `upgrade`, ADR 0096): `scripts/veyqctl.py`'s
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

Assertion 6 was bought separately and just as concretely (2026-08-17). `scripts/instance_backup.py` builds
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
moved behind a dispatch, and the minor into the history; this guard holds both.

Assertion 9 came with folding `container.yml` into `ci.yml` (cr-one-pipeline-per-commit, 2026-10-16), no incident:
the build stopped needing the tests, and from then on the publish's `needs:` is the only thing between a red stage
and a published image — a stage added to the file and forgotten there would be one a release never waited for.
Assertion 10 came with lane L1's job (cr-stack-e2e phase 4), no incident: assertion 9 reads the jobs the file has, so
deleting the lane — or renaming its job and dropping it from the publish's `needs:` in the same edit — was green there.
Its limit is assertion 9's: it reads the file, and proves no run ever waited.

Assertion 11 came with the GitHub Release (cr-veyqctl phase 5), and building it found a defect before any publish:
the template was carried as `.env.example`, and GitHub renames a release asset whose name starts with a period (its
REST docs, "Upload a release asset"), so every `veyqctl install` from GitHub would have fetched a name the release does
not have. The producer now names it `env.example` and refuses a recipe file GitHub would rename, and assertion 4 holds
`veyqctl`'s asset names to what the producer writes.

Assertion 11's two signatures came with inbox #469 (cr-signed-release-manifest), no incident — no release exists —
but a hole in the design that was there from the first release: the manifest was unsigned, so whoever controls the
source (a mirror, a local directory) could serve a forged manifest with a forged compose file that matched it. The
images stayed safe by their own signatures; the recipe, which can mount the host or add an unverified container, was
exactly as trustworthy as its source. Collecting every `sign-blob` line came with it: the old check read the first
match only, so a correct step that signed the manifest first read red.

Assertion 8 came with the chart's OCI release (same CR): no incident, the chart had never been published. Each of
its halves was bitten by injection before it counted, and the measurement that shaped it is in ADR 0064 — `helm
push` appends the chart's name to the target, which is why the target ends in `/charts`.

**A note for whoever edits this next, because the first run of assertion 5 tripped on it:** this guard scans
durable state for a literal, and its own file is durable state, so spelling that literal here makes the guard
flag itself. Describe the flag, do not write it. The repair that suggests itself — excluding this file from the
scan — puts a hole in exactly the place a future instruction would hide.

**Its limit — what a green run does NOT prove.** It reads **files**, and executes exactly one of them (the
version step's shell, against a history it built — so `fetch-depth: 0` on the real checkout is proven only by a
run, where its absence is a loud red at the shallow refusal). It cannot prove a publish ever ran, that a tag was created, that a republish was
refused, or that the registry holds what the workflow says it would push — this repository has **no remote**
and no workflow has ever executed, so the publish path stays an explicitly stated ADR 0040 gap. It says nothing
about the backup manifest's `app.version` (its observable is a capture run against a live instance, not a file),
and its scan for written-down image tags covers `infra/` and the Run pages only: `docs/` quotes the *old, wrong*
shapes on purpose, as the defect being described.

Assertion 4's limits. **The guard and the producer share one derivation**, so a way of reading a file that the
derivation does not know — a Compose key added after it was written — is missed by both, and green; the spellings
it knows are the list above. **An absolute host path is the host's, unchecked**: `/srv/…` or `~/…` in the compose
file is neither carried nor refused. It runs the carry step, never the whole producer (the release number needs the
full history, and this guard's checkout may be shallow), and never a published release: that a GitHub Release holds
these assets is a publish's to prove.

Assertion 11's limits. It reads the step's shell as lines, not as a program: the producer's output directory is the
first positional argument after `scripts/release_assets.py`, and the upload must name `<that>/v$VERSION/*` literally
(`${x}` and `$x` read alike) — an indirection through another variable is a false red, never a false green. That the
glob expands to every file is the producer's to keep true: it refuses a name with a leading period, the one kind a
`*` skips. A writer to a GitHub Release it does not recognise (another CLI, an action named otherwise) is not held.
It cannot prove `gh release create` ran, that the repository's immutable-releases setting is on, or which name GitHub
gave each asset — no remote exists, so the GitHub Release is a statement about a file like the rest of the publish.
That a `sign-blob` line names the manifest is not that cosign signed it: the step's shell has run only locally, with
stand-ins for `cosign` and `gh`, and that the bundle verifies is `veyqctl`'s self-test's, with a stand-in for cosign.
The between-lines check knows two ways to touch the manifest — the producer, and a line naming `manifest.json` — and a
rewrite through another variable or a script that writes it unnamed is not held.

Assertion 3's gating is read as a conservative model of GitHub's expression language: an `if:` counts as gated
only if it is a pure `&&` chain with the dispatch (or release) test as one term, so an equivalent condition
spelled any other way is a false red, never a false green. A registry write it does not recognise — a new tool,
or an action other than the login and build-push ones — is not held; the recognised set is the regex and the two
action names in the code.

**Assertion 5's own limit, and it is deliberate: it is scoped to the RELEASE RECIPE and says nothing about
`infra/docker-compose.yml`.** A chart deploys the recipe, not the dev stack, and a dev-only `build:` is a
legitimate thing to want — so forbidding one from a guard whose subject is *releases* would be this guard
overreaching into a file it has no argument about. It also reads the compose file rather than a registry, so
"the workflow publishes this name" means the matrix says so, never that a push ever succeeded (see the
no-remote limit above).

Assertion 6 adds two limits of its own, and the first is the one that matters. **It reads the file, so it says
nothing about the project the operator actually brought their stack up under.** `docker compose -p something-else
-f infra/docker-compose.prod.yml up -d` overrides the declared name, and so does `COMPOSE_PROJECT_NAME` — a
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
`veyqctl` it reads the two constants, not the `cosign verify` call that passes them: `scripts/veyqctl_selftest.py`
holds that call to exactly those two values.
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

ROOT = pathlib.Path(__file__).resolve().parent.parent

VERSION_FILE = ROOT / "VERSION"
WORKFLOW = ROOT / ".github/workflows/ci.yml"
INFRA = ROOT / "infra"

# `VERSION` holds the major alone; the minor is a commit count, so no tree can hold it (ADR 0057 §1).
MAJOR = re.compile(r"^([0-9]+)$")
# `<major>.<minor>` — a release's whole identity. Not three parts: the pipeline emits `<major>`, `<major>.<minor>`,
# `<major>.<minor>-SNAPSHOT`, `sha-<sha>` and `latest`, and a patch component would name an artifact with no producer.
RELEASE_VERSION = re.compile(r"^([0-9]+)\.([0-9]+)$")

# Step ids this guard anchors on. A rename makes it say "step not found" rather than pass vacuously.
VERSION_JOB, VERSION_STEP = "version", "veyq-version"
PUBLISH_JOB = "publish"
REFUSE_STEP, TAG_STEP = "assert-release-unpublished", "release-tag"
BUILD_JOB = "build"
# Lane L1's orchestrator: assertion 10 finds the lane's job by running it, never by the job's name.
LANE_ORCHESTRATOR = "scripts/run_stack_e2e.py"
CHART_DIR = ROOT / "charts/veyq"

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
    "RELEASE": "${{ inputs.release }}",
}

# What a publish is gated on, and what a release-only step is gated on (ADR 0057 §1–2).
DISPATCH = "github.event_name == 'workflow_dispatch'"
RELEASE_KIND = "needs.version.outputs.kind == 'release'"
# A `run:` that writes to a registry or to the remote — a GitHub Release included. `uses:` writers are recognised by
# action below. `cosign sign` matches `cosign sign-blob` too: it writes to the transparency log.
REGISTRY_WRITE_RUN = re.compile(
    r"\b(?:helm\s+push|helm\s+registry\s+login|cosign\s+sign|imagetools\s+create|docker\s+push|docker\s+login|git\s+push|oras\s+push"
    r"|gh\s+release\s+(?:create|upload|edit|delete|delete-asset))\b"
)
# Assertion 11: the one GitHub Release, and anything else that would write to one.
UNSCHEDULED_INPUT = "unscheduled_major"
GH_RELEASE_CREATE = re.compile(r"\bgh\s+release\s+create\b")
RELEASE_ASSET_WRITE = re.compile(
    r"\bgh\s+release\s+(?:upload|edit|delete|delete-asset)\b|uploads\.github\.com|\bgh\s+api\b[^\n]*/releases"
)
RELEASE_ACTION = re.compile(r"release", re.IGNORECASE)  # an action's `owner/name`: softprops/action-gh-release, …
IMAGE_SIGN = re.compile(r"\bcosign\s+sign\s")  # an image or the chart, not `sign-blob`
SIGN_BLOB = re.compile(r"\bcosign\s+sign-blob\b")
PRODUCER = re.compile(r"scripts/release_assets\.py\b")
NEW_BUNDLE_FORMAT = "--new-bundle-format"
FIRST_PUBLISH_WRITE = re.compile(r"\b(?:imagetools\s+create|cosign\s+sign|helm\s+push|git\s+push|gh\s+release)\b")


def non_release_branches(major: str) -> list[str]:
    """Adversarial branch names: each must be refused by the version step AND by the documented verify.

    A probe list, not an anchor set — the branches that DO publish are derived from the scenarios' outcomes.
    """
    return ["master", "develop", "main-next", f"release/{major}.x-rc", f"release/{major}.x/hotfix"]


# Filled by assertion 2's scenarios: every branch a push to which the version step turns into a publish.
publishing_branches: list[str] = []

errors: list[str] = []


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
        "infra/.env.example tells the operator to pin an image tag that has no producer."
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


def step_by_id(doc: dict, job: str, step_id: str) -> dict | None:
    return next((s for s in job_steps(doc, job) if s.get("id") == step_id), None)


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


def build_history(tmp: pathlib.Path, major: str) -> tuple[pathlib.Path, pathlib.Path]:
    """A full clone and a shallow clone of a history whose VERSION last changed EXPECTED_MINOR commits ago."""
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
    full = tmp / "full"
    shallow = tmp / "shallow"
    git(tmp, "clone", "-q", "--no-local", str(origin), str(full))
    git(tmp, "clone", "-q", "--depth", "1", f"file://{origin}", str(shallow))
    return full, shallow


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
        snapshot = f"{release}-SNAPSHOT"

        def dispatch(branch: str, release_input: bool) -> dict[str, str]:
            return {"EVENT_NAME": "workflow_dispatch", "REF_NAME": branch, "RELEASE": str(release_input).lower()}

        quiet = {"publish_branch_tags": "false", "publish_latest": "false"}
        # (label, shallow?, env, expected exit, expected outputs)
        scenarios = [
            (
                "a snapshot dispatch on main",
                False, dispatch("main", False), 0,
                {"kind": "snapshot", "version": snapshot, "major": major, **quiet},
            ),
            (
                "a release dispatch on main",
                False, dispatch("main", True), 0,
                {"kind": "release", "version": release, "major": major, "publish_branch_tags": "true",
                 "publish_latest": "true"},
            ),
            (
                f"a release dispatch on release/{major}.x",
                False, dispatch(f"release/{major}.x", True), 0,
                {"kind": "release", "version": release, "publish_branch_tags": "true", "publish_latest": "false"},
            ),
            (
                f"a snapshot dispatch on release/{major}.x",
                False, dispatch(f"release/{major}.x", False), 0,
                {"kind": "snapshot", "version": snapshot, **quiet},
            ),
            (
                # A push is the dry run: it builds and publishes nothing, whatever the branch.
                "a push to main",
                False, {"EVENT_NAME": "push", "REF_NAME": "main", "RELEASE": ""}, 0,
                {"kind": "none", "version": snapshot, **quiet},
            ),
            (
                "a pull request",
                False, {"EVENT_NAME": "pull_request", "REF_NAME": "12/merge", "RELEASE": ""}, 0,
                {"kind": "none", "version": snapshot, **quiet},
            ),
            (
                # A shallow clone counts 1: the step must refuse it rather than stamp a wrong minor that looks right.
                "a release dispatch on main from a shallow checkout",
                True, dispatch("main", True), 1, {},
            ),
            (
                "a push to main from a shallow checkout",
                True, {"EVENT_NAME": "push", "REF_NAME": "main", "RELEASE": ""}, 1, {},
            ),
        ] + [
            # A ref that disagrees with the tree, and branches that are not release branches, must publish nothing
            # of EITHER kind: whatever publishes also signs, and assertion 7 holds the documented verify to
            # rejecting exactly these (ADR 0057 §3: what signs and what verifies agree). `master` is the one a
            # publish accepted until cr-image-provenance.
            (
                f"a {'release' if as_release else 'snapshot'} dispatch on {branch}, which is not a release branch "
                f"of this tree",
                False, dispatch(branch, as_release), 1, {},
            )
            for branch in [f"release/{int(major) + 7}.x", *non_release_branches(major)]
            for as_release in (True, False)
        ]
        try:
            with tempfile.TemporaryDirectory(prefix="release_identity_") as tmp:
                full, shallow = build_history(pathlib.Path(tmp), major)
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
                for label, use_shallow, env, expect_rc, expect in scenarios:
                    try:
                        rc, written, message = run_scenario(script, shallow if use_shallow else full, env)
                    except subprocess.TimeoutExpired:
                        fail(f"the `{VERSION_STEP}` step hung for {label} — a version derivation that waits is waiting on a network")
                        continue
                    if env["EVENT_NAME"] == "workflow_dispatch" and rc == 0:
                        publishing_branches.append(env["REF_NAME"])
                    if expect_rc == 0 and rc != 0:
                        fail(f"the `{VERSION_STEP}` step fails for {label}: {message or f'exit {rc}'}")
                        continue
                    if expect_rc != 0:
                        if rc == 0:
                            fail(
                                f"the `{VERSION_STEP}` step accepts {label} and would stamp "
                                f"`{written.get('version')}` (kind `{written.get('kind')}`) — a publish names "
                                f"itself from the full history and runs only from main or release/<major>.x, so "
                                f"a ref that disagrees with VERSION, any other branch, and a shallow checkout "
                                f"must fail rather than publish a signed artifact under a wrong name"
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
        for name in sorted(stamped - targets[0]):
            fail(
                f"`{where}`'s `{target}` stage does not declare `ARG {name}`, which the `{BUILD_JOB}` job passes — "
                f"Docker drops an undeclared build-arg without failing, so the published image carries no {name}"
            )
        for name, args in stages:
            if name == target:
                continue
            for leaked in sorted(stamped & args):
                fail(
                    f"`{where}` declares `ARG {leaked}` in "
                    + (f"its `{name}` stage" if name else "a stage with no name or before its first FROM")
                    + f", not only in `{target}`, the stage a publish ships. A build stage that reads the stamp "
                    f"compiles it into what the image serves, and the web client is served before any login — the "
                    f"precise release never goes there (ADR 0057 §4). An export names the major instead."
                )


# ---- 3. a publish refuses to replace a release, and leaves one ref -------------------------------------------

if workflow_doc:
    # `on:` is a YAML 1.1 boolean, so PyYAML keys it as True. Enumerate the spelling rather than guess it.
    triggers = workflow_doc.get("on", workflow_doc.get(True)) or {}
    if (triggers.get("push") or {}).get("tags"):
        fail(
            f"{WORKFLOW.relative_to(ROOT)} triggers on a pushed tag. The publish CREATES `v<major>.<minor>` "
            f"(ADR 0057 §2), so a tag push can only ask for a version that is already published — which the "
            f"`{REFUSE_STEP}` step refuses by design. Keeping both means every release is followed by a red run."
        )

    publish_perms = ((workflow_doc.get("jobs") or {}).get(PUBLISH_JOB) or {}).get("permissions") or {}
    if publish_perms.get("contents") != "write":
        fail(
            f"the `{PUBLISH_JOB}` job cannot create the `v<major>.<minor>` ref: it needs "
            f"`permissions: contents: write` (ADR 0057 §2 — one release, one name, one tree)"
        )

    # The dispatch input that chooses the kind. A boolean that defaults to false is what makes a snapshot the
    # default and a release something asked for — a string input, or a default of true, inverts that.
    release_input = ((triggers.get("workflow_dispatch") or {}).get("inputs") or {}).get("release") or {}
    if release_input.get("type") != "boolean" or release_input.get("default") is not False:
        fail(
            f"{WORKFLOW.relative_to(ROOT)}: `workflow_dispatch` must take a boolean input `release` that defaults "
            f"to false (found {release_input or 'none'}) — a publish is a snapshot unless a release is asked for "
            f"explicitly (ADR 0057 §1)"
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
        if not text or "||" in text or re.search(r"!(?!=)", text):
            return False
        return any(term.strip() == conjunct for term in text.split("&&"))

    for step_id, must_contain, why in (
        (
            REFUSE_STEP,
            ("exit 1",),
            "a release whose tag already exists must FAIL rather than replace it: a transient registry error "
            "would otherwise be indistinguishable from `nothing published yet`, and an operator who pinned "
            "that tag receives different bits with no signal (ADR 0057 §1)",
        ),
        (
            TAG_STEP,
            ("refs/tags/v${VERSION}", "git push"),
            "a published image has exactly one git ref, `v<major>.<minor>`, created by the publish — without "
            "it the deployment files belonging to a version cannot be found (ADR 0057 §2)",
        ),
    ):
        step = step_by_id(workflow_doc, PUBLISH_JOB, step_id)
        if step is None:
            fail(f"{WORKFLOW.relative_to(ROOT)}: no step `id: {step_id}` in job `{PUBLISH_JOB}` — {why}")
            continue
        body = str(step.get("run") or "")
        if VERSION_EXPR not in str(step.get("env") or {}):
            fail(
                f"the `{step_id}` step does not read `{VERSION_EXPR}` — it must key on the version the tree "
                f"derived, never on a registry query or a hand-typed literal"
            )
        for needle in must_contain:
            if needle not in body:
                fail(f"the `{step_id}` step does not contain `{needle}` — {why}")
        if "--force" in body or " -f " in body:
            fail(
                f"the `{step_id}` step forces: a release that can be overwritten names a moving artifact, and "
                f"reporting it accurately would still mislead (uc.admin.identify-instance-release § Quality)"
            )
        if not gated_on(step.get("if"), RELEASE_KIND):
            fail(
                f"the `{step_id}` step runs when `{RELEASE_KIND}` is not true (if: `{step.get('if')}`) — only a "
                f"release refuses a republish and gets a git ref; a snapshot is the one name that moves and names "
                f"a build, not a release (ADR 0057 §1–2)"
            )

    # Every registry write is reachable only from a dispatch (ADR 0057 §1: nothing is published by a push). The
    # set is derived from what the steps DO — a login or build-push action, or a `run:` that pushes, logs in or
    # signs — never listed, so a write added to any job is held without anyone remembering to add it here.
    jobs = workflow_doc.get("jobs") or {}
    publish_job = jobs.get(PUBLISH_JOB) or {}
    if not gated_on(publish_job.get("if"), DISPATCH):
        fail(
            f"the `{PUBLISH_JOB}` job runs when `{DISPATCH}` is not true (if: `{publish_job.get('if')}`) — a push "
            f"or a pull request would then publish, which is the red run on every commit this pipeline exists to "
            f"end (ADR 0057 §1)"
        )
    registry_writes = 0
    for job_name, job in sorted(jobs.items()):
        if not isinstance(job, dict):
            continue
        job_gated = gated_on(job.get("if"), DISPATCH)
        for step in job_steps(workflow_doc, job_name):
            uses = str(step.get("uses") or "")
            writes = []
            if uses.startswith("docker/login-action@"):
                writes.append("logs in to a registry")
            if uses.startswith("docker/build-push-action@"):
                push = (step.get("with") or {}).get("push")
                if push is not False and str(push).lower() != "false":
                    writes.append("pushes an image")
                    if gated_on(push, DISPATCH):
                        registry_writes += 1
                        writes.pop()
            match = REGISTRY_WRITE_RUN.search(str(step.get("run") or ""))
            if match:
                writes.append(f"runs `{match.group(0)}`")
            registry_writes += len(writes)
            if writes and not (job_gated or gated_on(step.get("if"), DISPATCH)):
                fail(
                    f"job `{job_name}`, step `{step.get('name') or step.get('id')}` {' and '.join(writes)} on an "
                    f"event other than `workflow_dispatch` — a push and a pull request are dry runs that write to "
                    f"no registry (ADR 0057 §1). Gate the step, its job, or the push input on `{DISPATCH}`."
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
    stage_jobs = sorted(str(j) for j, v in jobs.items() if j != PUBLISH_JOB and isinstance(v, dict))
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
            fail(f"lane L1's job `{lane}` does not need `{BUILD_JOB}` — on a dispatch it installs the images that job "
                 f"pushed, so it must run after it (ADR 0090 decision 2)")
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

    # ---- 11. a release is one immutable GitHub Release, created last, in one call --------------------------------
    # ADR 0096 decision 3: the recipe, `veyqctl` (signed) and the manifest are the assets of a GitHub Release on the
    # ref. An immutable release takes no asset after it is published, so the one `gh release create` carries the
    # producer's whole output; it runs for a release only, after every signature and the ref; and nothing uploads later.
    unscheduled_input = ((triggers.get("workflow_dispatch") or {}).get("inputs") or {}).get(UNSCHEDULED_INPUT) or {}
    if unscheduled_input.get("type") != "boolean" or unscheduled_input.get("default") is not False:
        fail(
            f"{WORKFLOW.relative_to(ROOT)}: `workflow_dispatch` must take a boolean input `{UNSCHEDULED_INPUT}` that "
            f"defaults to false (found {unscheduled_input or 'none'}) — an unscheduled major is marked only when asked "
            f"for, and the producer refuses it on a release that does not change the major"
        )
    release_steps = [
        (job_name, index, step)
        for job_name in sorted(jobs)
        for index, step in enumerate(job_steps(workflow_doc, job_name))
        if GH_RELEASE_CREATE.search(str(step.get("run") or ""))
    ]
    late_uploads = [
        f"job `{job_name}`, step `{step.get('name') or step.get('id')}`"
        for job_name in sorted(jobs)
        for step in job_steps(workflow_doc, job_name)
        if RELEASE_ASSET_WRITE.search(str(step.get("run") or ""))
        or RELEASE_ACTION.search(str(step.get("uses") or "").split("@", 1)[0])
    ]
    for where in late_uploads:
        fail(
            f"{where} writes to a GitHub Release other than by the one `gh release create` — an immutable release "
            f"takes no asset after it is published, so a later upload fails on GitHub, and before the setting is on it "
            f"changes a release after its files were the ones published (ADR 0096 decision 3)"
        )
    if len(release_steps) != 1 or release_steps[0][0] != PUBLISH_JOB:
        fail(
            f"{WORKFLOW.relative_to(ROOT)} runs `gh release create` in {len(release_steps)} step(s) "
            f"({', '.join(f'{j}' for j, _, _ in release_steps) or 'none'}) — a release is exactly one GitHub Release, "
            f"created by the `{PUBLISH_JOB}` job (ADR 0096 decision 3)"
        )
    else:
        _, release_index, release_step = release_steps[0]
        publish_steps = job_steps(workflow_doc, PUBLISH_JOB)
        body = str(release_step.get("run") or "")
        lines = [line.strip() for line in body.replace("\\\n", " ").splitlines()]
        named = f"the `{release_step.get('id') or release_step.get('name')}` step"
        if not gated_on(release_step.get("if"), RELEASE_KIND):
            fail(
                f"{named} creates a GitHub Release when `{RELEASE_KIND}` is not true (if: `{release_step.get('if')}`) — "
                f"a snapshot gets no GitHub Release (ADR 0096 decision 3), and `veyqctl` would install it as a release"
            )
        tag_index = next((i for i, s in enumerate(publish_steps) if s.get("id") == TAG_STEP), None)
        signing = [i for i, s in enumerate(publish_steps)
                   if i != release_index and IMAGE_SIGN.search(str(s.get("run") or ""))]
        if tag_index is None or release_index < tag_index:
            fail(
                f"{named} does not follow the `{TAG_STEP}` step — `gh release create --verify-tag` needs the ref to "
                f"exist, and a GitHub Release on a ref the publish has not created names a release that has no tree"
            )
        if not signing:
            fail(f"the `{PUBLISH_JOB}` job has no `cosign sign` step, so {named}'s place after the signatures checked NOTHING")
        elif release_index < max(signing):
            fail(
                f"{named} comes before a signing step of the `{PUBLISH_JOB}` job — a GitHub Release is published last, "
                f"so a release whose files an operator can fetch is one whose images and chart are signed"
            )

        def argv_of(pattern: re.Pattern) -> list[str] | None:
            for line in lines:
                if pattern.search(line):
                    try:
                        return shlex.split(line.split(" #", 1)[0])
                    except ValueError as e:
                        fail(f"{named}: cannot parse `{line[:80]}`: {e}")
                        return None
            return None

        def norm(token: str) -> str:
            return re.sub(r"\$\{(\w+)\}", r"$\1", token)

        create = argv_of(GH_RELEASE_CREATE) or []
        producer = argv_of(PRODUCER) or []
        if "--verify-tag" not in create:
            fail(
                f"{named}'s `gh release create` does not carry `--verify-tag` — without it a missing ref is CREATED by "
                f"GitHub from the default branch's tip, a GitHub Release on a tree no publish named (ADR 0057 §2)"
            )
        for flag in create:
            if flag.split("=", 1)[0] in ("--draft", "-d", "--prerelease", "-p"):
                fail(
                    f"{named}'s `gh release create` passes `{flag}` — a draft is no release `veyqctl` lists and a "
                    f"prerelease is one it never installs (ADR 0096 decision 4)"
                )
        out = None
        if producer:
            script_at = next(i for i, t in enumerate(producer) if t.endswith("scripts/release_assets.py"))
            out = next((norm(t) for t in producer[script_at + 1:] if not t.startswith("-")), None)
        if out is None:
            fail(f"{named} does not run `scripts/release_assets.py` with an output directory — the release's files "
                 f"are the producer's, never assembled by hand")
        else:
            whole = f"{out}/v$VERSION/*"
            if whole not in [norm(t) for t in create]:
                fail(
                    f"{named}'s `gh release create` does not upload `{whole}`, the producer's whole output for the "
                    f"release — every asset goes in the one call, because an immutable release takes none later"
                )
            # Every `sign-blob` of the step, not the first one: the release signs two files, and a lookup that stops
            # at the first match reads a correct step red as soon as the two lines trade places.
            create_at = next((i for i, line in enumerate(lines) if GH_RELEASE_CREATE.search(line)), len(lines))
            producer_at = [i for i, line in enumerate(lines) if PRODUCER.search(line)]
            blobs: dict[str, list[tuple[int, list[str]]]] = {}
            for at, line in enumerate(lines):
                if not SIGN_BLOB.search(line):
                    continue
                try:
                    argv = shlex.split(line.split(" #", 1)[0])
                except ValueError as e:
                    fail(f"{named}: cannot parse `{line[:80]}`: {e}")
                    continue
                blobs.setdefault(norm(argv[-1]), []).append((at, argv))
            for blob, bundle_name, why in (
                (f"{out}/v$VERSION/veyqctl", release_assets.VEYQCTL_BUNDLE,
                 "`veyqctl` is signed with the images' identity for the copy an operator fetches by hand, and the bundle "
                 "is an asset (ADR 0096 decision 3)"),
                (f"{out}/v$VERSION/{release_assets.MANIFEST}", release_assets.MANIFEST_BUNDLE,
                 "`veyqctl` verifies the manifest's bundle before it trusts any sha256 or digest in it, so an unsigned "
                 "manifest is a recipe exactly as trustworthy as its source (inbox #469, ADR 0057 §3)"),
            ):
                signed = blobs.get(blob, [])
                want = f"{out}/v$VERSION/{bundle_name}"
                if len(signed) != 1:
                    fail(f"{named} runs {len(signed)} `cosign sign-blob` of `{blob}`, not one — {why}")
                    continue
                at, argv = signed[0]
                bundles = [norm(argv[i + 1]) for i, t in enumerate(argv[:-1]) if t == "--bundle"]
                if bundles != [want]:
                    fail(f"{named} signs `{blob}` with `--bundle` {bundles or 'missing'}, not `{want}` — the bundle is "
                         f"an asset of the one upload, under the name `veyqctl` and the Run page fetch; {why}")
                if NEW_BUNDLE_FORMAT not in argv:
                    fail(f"{named} signs `{blob}` without `{NEW_BUNDLE_FORMAT}` — a GitHub Release is immutable, so a "
                         f"bundle in the legacy format cosign is retiring can never be re-signed, and `veyqctl` needs "
                         f"cosign 3, which writes and reads the Sigstore bundle by default")
                if not producer_at or at < max(producer_at):
                    fail(f"{named} signs `{blob}` before the producer writes it — the signature would be of another file")
                if at > create_at:
                    fail(f"{named} signs `{blob}` after `gh release create` — the bundle would miss the one call")
                if bundle_name == release_assets.MANIFEST_BUNDLE:
                    rewrite = [lines[i] for i in range(at + 1, create_at)
                               if PRODUCER.search(lines[i])
                               or (release_assets.MANIFEST in lines[i] and not SIGN_BLOB.search(lines[i]))]
                    if rewrite:
                        fail(f"{named} touches the manifest between its `cosign sign-blob` and `gh release create` "
                             f"(`{rewrite[0][:80]}`) — the manifest uploaded must be the one signed, or every "
                             f"`veyqctl install` refuses the release")
            for blob in sorted(set(blobs) - {f"{out}/v$VERSION/veyqctl", f"{out}/v$VERSION/{release_assets.MANIFEST}"}):
                fail(f"{named} runs `cosign sign-blob` on `{blob}`, which is neither `veyqctl` nor the manifest — this "
                     f"guard holds exactly those two signatures")
        unscheduled_env = str(release_step.get("env") or {})
        if f"inputs.{UNSCHEDULED_INPUT}" not in unscheduled_env or "--unscheduled" not in body:
            fail(f"{named} does not hand `inputs.{UNSCHEDULED_INPUT}` to the producer as `--unscheduled` — the manifest "
                 f"would never mark an unscheduled major (ADR 0097)")
        # The producer's refusals come before anything is published: a refusal at the GitHub Release step would leave
        # images, a chart and a ref with no GitHub Release.
        first_write = next((i for i, s in enumerate(publish_steps)
                            if FIRST_PUBLISH_WRITE.search(str(s.get("run") or ""))), None)
        prechecked = any(
            "scripts/release_assets.py" in str(s.get("run") or "")
            and f"inputs.{UNSCHEDULED_INPUT}" in str(s.get("env") or {})
            for s in publish_steps[: first_write if first_write is not None else 0]
        )
        if not prechecked:
            fail(
                f"no step of the `{PUBLISH_JOB}` job runs `scripts/release_assets.py` with `inputs.{UNSCHEDULED_INPUT}` "
                f"before its first push — a producer refusal (an unscheduled major that is none) would then come after "
                f"the images, the chart and the ref are out, leaving a release with no GitHub Release"
            )


# ---- 4. the release carries every file its recipe reads; written-down tags name a shape the pipeline emits --

def veyqctl_constants(pattern: str) -> dict[str, str]:
    """`scripts/veyqctl.py`'s module-level string constants whose names match `pattern`, read without importing it."""
    path = ROOT / "scripts/veyqctl.py"
    if not path.is_file():
        return {}
    found = {}
    for node in ast.parse(path.read_text()).body:
        if (isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                and re.fullmatch(pattern, node.targets[0].id)
                and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str)):
            found[node.targets[0].id] = node.value.value
    return found


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
            # `cosign sign-blob`, which assertion 11 holds to these names), so they are names a release has too.
            produced = {p.name for p in carried.iterdir()} | {
                release_assets.MANIFEST, release_assets.VEYQCTL_BUNDLE, release_assets.MANIFEST_BUNDLE}
            veyqctl_assets = veyqctl_constants(r"[A-Z]+_ASSET")
            if not veyqctl_assets:
                fail("scripts/veyqctl.py names no `*_ASSET` constant, so the names it fetches checked NOTHING")
            for constant, name in sorted(veyqctl_assets.items()):
                if name not in produced:
                    fail(
                        f"scripts/veyqctl.py's `{constant}` is `{name}`, which scripts/release_assets.py does not write "
                        f"(it writes {', '.join(sorted(produced))}) — `veyqctl install` would miss it in every release"
                    )

written_tags = 0
# Tracked files only, as in assertion 9's flag scan. A gitignored `infra/.env` is one operator's local pin (a
# dev build's `veyq:cr162`, say), not something this repository tells anyone to run — scanning it made the
# guard red on that machine and green in CI over the same commit.
RUN_PAGES = ROOT / "site/src/content/docs/run"  # the operator procedures (ADR 0087), which write tags down too
tracked_infra = subprocess.run(
    ["git", "ls-files", "-z", "--", str(INFRA.relative_to(ROOT)), str(RUN_PAGES.relative_to(ROOT))],
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

PROD_COMPOSE = INFRA / "docker-compose.prod.yml"

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
    # ---- 8. the chart is the release's third artifact ---------------------------------------------------------
    chart_meta = yaml.safe_load((CHART_DIR / "Chart.yaml").read_text()) if (CHART_DIR / "Chart.yaml").is_file() else {}
    chart_name = str((chart_meta or {}).get("name") or "")
    owner_expr = "ghcr.io/${{ github.repository_owner }}"
    refuse = step_by_id(workflow_doc, PUBLISH_JOB, REFUSE_STEP) or {}
    # Whole words of the step's env, with the owner expression kept as one word (it contains spaces), so
    # `…/veyq` is not satisfied by `…/veyq-admin` or by `…/charts/veyq`.
    refuse_env = (
        " ".join(str(v) for v in (refuse.get("env") or {}).values())
        .replace("${{ github.repository_owner }}", "\0OWNER\0")
        .split()
    )
    refuse_env = [word.replace("\0OWNER\0", "${{ github.repository_owner }}") for word in refuse_env]
    matrix_images = sorted(published_images(WORKFLOW.read_text())) if WORKFLOW.is_file() else []
    if not chart_name:
        fail(f"{(CHART_DIR / 'Chart.yaml').relative_to(ROOT)} names no chart, so the chart's release cannot be held")
    for artifact in [*matrix_images, f"charts/{chart_name}"]:
        if f"{owner_expr}/{artifact}" not in refuse_env:
            fail(
                f"the `{REFUSE_STEP}` step does not check `{owner_expr}/{artifact}` — a release with that artifact "
                f"already out would be published again around it, and a release whose images and chart disagree "
                f"is a partial one (ADR 0064, ADR 0065)"
            )
    package_steps = [s for s in job_steps(workflow_doc, PUBLISH_JOB) if "helm package" in str(s.get("run") or "")]
    if len(package_steps) != 1:
        fail(
            f"the `{PUBLISH_JOB}` job has {len(package_steps)} step(s) running `helm package`; the chart ships "
            f"with every publish, packaged exactly once (ADR 0064)"
        )
    for step in package_steps:
        body = str(step.get("run") or "")
        env = {str(k): str(v) for k, v in (step.get("env") or {}).items()}
        if env.get("VERSION") != VERSION_EXPR:
            fail(
                f"the chart packaging step's `VERSION` is `{env.get('VERSION')}`, not `{VERSION_EXPR}` — the "
                f"chart's version must be the release's, never the in-tree placeholder or a literal"
            )
        for flag in ('--version "$VERSION"', '--app-version "$VERSION"'):
            if flag not in body:
                fail(
                    f"the chart packaging step does not pass `{flag}` — the chart's `version` and `appVersion` "
                    f"are both the release version, set at packaging (ADR 0064); `appVersion` is the image tag "
                    f"the values default to, so a chart packaged without it deploys the placeholder"
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
            f"an unqualified `down -v` reaches the production volumes and `scripts/instance_backup.py`'s "
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

# The operator's verification is a page of the docs site's Run area (ADR 0087), kept `.md` with its commands in
# plain fenced blocks so this guard can read them; it moved there from infra/README.md, which keeps the dev stack.
VERIFY_PAGE = ROOT / "site/src/content/docs/run/verify-a-release.md"
CHART = ROOT / "charts/veyq/Chart.yaml"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
# The registry the publish pushes to. The owner is not taken from the workflow — there it is
# `github.repository_owner`, which is whoever runs it, a fork included — but from the chart's `home`, the one
# file of state that names the upstream.
REGISTRY = "ghcr.io"
UPSTREAM = re.compile(r"^https://github\.com/([^/\s]+)/([^/\s]+?)/?$")
FENCE = re.compile(r"^```[^\n]*\n(.*?)^```", re.MULTILINE | re.DOTALL)
# The operator's tool runs the same verification (ADR 0096): the identity it hands cosign is two module-level string
# constants, read here without importing it.
VEYQCTL = ROOT / "scripts/veyqctl.py"
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


def flag_values(argv: list[str], name: str) -> list[str]:
    values = []
    for index, token in enumerate(argv):
        if token == name and index + 1 < len(argv):
            values.append(argv[index + 1])
        elif token.startswith(name + "="):
            values.append(token.split("=", 1)[1])
    return values


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
    workflow_file = WORKFLOW.relative_to(ROOT).as_posix()

    def san(ref: str, workflow: str = workflow_file, slug: str = f"{owner}/{repo}") -> str:
        """The certificate identity keyless signing from a GitHub workflow produces."""
        return f"https://github.com/{slug}/{workflow}@{ref}"

    accepted = {f"a publish dispatched on `{b}`": san(f"refs/heads/{b}") for b in publishing_branches}
    rejected = {
        **{
            f"`{w.relative_to(ROOT).as_posix()}` (another workflow) on main": san(
                "refs/heads/main", workflow=w.relative_to(ROOT).as_posix()
            )
            for w in sorted(WORKFLOW.parent.glob("*.y*ml"))
            if w != WORKFLOW
        },
        **{f"a push to `{b}`, not a release branch": san(f"refs/heads/{b}") for b in
           non_release_branches(version_match.group(1) if version_match else "1")},
        "a pull request ref": san("refs/pull/1/merge"),
        "a tag ref": san(f"refs/tags/v{version_value}.0"),
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
        f"{len(accepted)} publishing branch identities and reject {len(rejected)} others"
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
                    f"workflow publishes and signs from that branch, so a genuine release fails to verify"
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
    for key, image in (("image", chart_name), ("admin.image", f"{chart_name}-admin")):
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
            f"{workflow_file} states a verification identity of its own. The identity is stated once, in "
            f"{VERIFY_PAGE.relative_to(ROOT)}, where this guard holds it to what signs — a second copy is one "
            f"nothing checks, and the one the workflow carried accepted any workflow on any ref."
        )

if not written_tags and not errors:
    # A guard that checked nothing and a guard whose subject is sound report the same green.
    fail(
        f"no literal veyq image tag found anywhere under {INFRA.relative_to(ROOT)}/ or "
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
    f"({len(publishing_branches)} publishing branch(es), a shallow checkout refused), every registry write waits for "
    f"a dispatch, the stamp is declared by the {stamped_targets} published target stage(s) and no other, a release "
    f"refuses a republish, creates its v{version_value}.<minor> ref and last its one GitHub Release in one call, the publish waits on every one of the "
    f"{len([j for j in (workflow_doc.get('jobs') or {}) if j != PUBLISH_JOB])} other jobs as a green stage, the producer carries "
    f"the {len(recipe_assets)} recipe file(s) the derivation names ({', '.join(recipe_assets)}), {written_tags} written-down image "
    f"tag(s) name a shape the pipeline emits, and the production stack names its own project "
    f"(`name: {prod_project}`) with nothing in durable state instructing the flag it replaced{verify_summary}"
)
