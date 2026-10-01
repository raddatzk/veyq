#!/usr/bin/env python3
"""Guard that a veyq release has an identity, that the identity comes from the tree, and that every artifact
naming a release names a shape the pipeline actually emits (ADR 0057).

**What it asserts.** Seven things, in the order a release happens:

  1. **`VERSION` exists at the repository root and holds `<major>.<minor>`.** That file *is* the release
     identity (ADR 0057 §1) — a checkout answers what it is, with no network call and no registry.
  2. **The publish workflow derives the version from that file, offline.** The `version` job's compute step is
     *executed* here, in a throwaway directory holding a copy of `VERSION`, under four scenarios — a `main`
     push, a pull request, a matching `v<major>.<minor>` tag, and a tag that disagrees with the tree (which
     must FAIL). Executing it is the only honest way to assert what a publish *would* stamp; reading it would
     only assert what it looks like. The step is also held to declaring no network tool at all, because a
     version a registry decides is not an identity (ADR 0057 § Rejected).
  3. **A publish refuses to replace a release, and leaves exactly one git ref.** The `publish` job carries a
     step that fails when the image tag or `v<major>.<minor>` already exists, and a step that creates that ref
     — neither with `--force`, and both keyed on the *tree-derived* version rather than on anything else. The
     `build` job stamps the same value into the image as `VEYQ_VERSION`, which is what the running instance
     later reports.
  4. **The deployment recipe travels in the image, in every runtime target.** Every `runtime-*` stage of the
     `Dockerfile` (one today, `runtime-jvm`) copies `infra/docker-compose.prod.yml` and `infra/.env.example`
     from their real in-tree paths, to the same destination, and that destination is neither `/work` (it holds
     `quarkus-app/`; a mount there freezes an upgrade — see the Dockerfile's own comment) nor under
     `/var/lib/veyq` (the config-secret volume). Plus: every veyq image tag written down under `infra/` matches
     a tag the pipeline emits.
  5. **Every service in the release recipe runs an image the operator can PULL.** No service in
     `infra/docker-compose.prod.yml` carries a `build:` stanza, and every veyq-namespaced image it names is one
     `.github/workflows/container.yml` actually publishes — the set is read out of that workflow's build
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
     `cosign verify` in `infra/README.md` pins GitHub's OIDC issuer and one certificate identity; that identity
     **accepts** the signing identity of every branch assertion 2's scenarios turn into a publish, and
     **rejects** every other workflow in `.github/workflows/`, non-release branches (which the version step must
     refuse as well), pull-request and tag refs, another owner, a repository whose name only starts with ours,
     and the right identity quoted inside another URL. Every image the build matrix publishes is verified, by
     digest and never by tag, and the workflow states no identity of its own. The upstream is the chart's
     `home`, not the workflow's `github.repository_owner`, which is whoever runs it — a fork included.

**The defect that bought it.** All of it was live when this guard was written (2026-08-11), and the first run
was the discovery: there was no `VERSION` file at all, so `container.yml`'s first command
(`tr -d '[:space:]' < VERSION`) died and **no image had ever been published** — while `infra/.env.example` told
the operator to pin `VEYQ_IMAGE=ghcr.io/<owner>/veyq:1.0.0`, a three-part shape this pipeline never emits, and
`infra/docker-compose.prod.yml`'s `${VEYQ_IMAGE:?…}` message repeated the invented shape as `…/veyq:1.2.3`. The
file had been deleted by a "cleanup" commit that also took `Dockerfile` and `scripts/run_web_e2e.py`; those two
were recovered and this one was not, and nothing noticed for the two months in which no container run happened.

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

**A note for whoever edits this next, because the first run of assertion 5 tripped on it:** this guard scans
durable state for a literal, and its own file is durable state, so spelling that literal here makes the guard
flag itself. Describe the flag, do not write it. The repair that suggests itself — excluding this file from the
scan — puts a hole in exactly the place a future instruction would hide.

**Its limit — what a green run does NOT prove.** It reads **files**, and executes exactly one of them (the
version step's shell). It cannot prove a publish ever ran, that a tag was created, that a republish was
refused, or that the registry holds what the workflow says it would push — this repository has **no remote**
and no workflow has ever executed, so the publish path stays an explicitly stated ADR 0040 gap. The trust chain
is also **buildless**: nothing here can inspect a *built* image, so assertion 4 proves the `Dockerfile`
**declares** the copy, never that the file is in the image — that is proven by building it and reading the file
back out, which is cr-release-identity's phase-2 done-when and not a guard. It says nothing about the backup
manifest's `app.version` (its observable is a capture run against a live instance, not a file), and its scan
for written-down image tags covers `infra/` only: `docs/` quotes the *old, wrong* shapes on purpose, as the
defect being described.

**Assertion 5's own limit, and it is deliberate: it is scoped to the RELEASE RECIPE and says nothing about
`infra/docker-compose.yml`.** A chart deploys the recipe, not the dev stack, and a dev-only `build:` is a
legitimate thing to want — so forbidding one from a guard whose subject is *releases* would be this guard
overreaching into a file it has no argument about. It also reads the compose file rather than a registry, so
"the workflow publishes this name" means the matrix says so, never that a push ever succeeded (see the
no-remote limit above).

Assertion 6 adds two limits of its own, and the first is the one that matters. **It reads the file, so it says
nothing about the project the operator actually brought their stack up under.** `docker compose -p something-else
-f infra/docker-compose.prod.yml up -d` overrides the declared name, and so does `COMPOSE_PROJECT_NAME` — a
green here means the *procedure* can no longer be wrong by default, never that a given host is right. Only a
tool that owns the operator's shell can close that, which is inbox #121's job and explicitly not this guard's.
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
registry (`ghcr.io`) is a constant here. And it cannot see whether an operator runs the command at all.
"""
from __future__ import annotations

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

ROOT = pathlib.Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "VERSION"
WORKFLOW = ROOT / ".github/workflows/container.yml"
DOCKERFILE = ROOT / "Dockerfile"
INFRA = ROOT / "infra"

# `<major>.<minor>` — the whole release identity. Not three parts: the pipeline emits `<major>`,
# `<major>.<minor>`, `sha-<sha>` and `latest`, and a patch component would name an artifact with no producer.
RELEASE_VERSION = re.compile(r"^([0-9]+)\.([0-9]+)$")

# Step ids this guard anchors on. A rename makes it say "step not found" rather than pass vacuously.
VERSION_JOB, VERSION_STEP = "version", "veyq-version"
PUBLISH_JOB = "publish"
REFUSE_STEP, TAG_STEP = "assert-release-unpublished", "release-tag"
BUILD_JOB = "build"

# The tree-derived version, as the workflow refers to it. Every release-naming step must key on THIS and not on
# a registry query, a `git describe`, or a hand-typed literal.
VERSION_EXPR = "${{ needs.version.outputs.version }}"

# `FROM <image> AS <stage>` — the same anchor validate_config_secret_persistence.py uses, for the same reason:
# there is more than one runtime target and a guard anchored on one is no guard.
STAGE = re.compile(r"^\s*FROM\s+\S+(?:\s+--\S+)*\s+AS\s+(\S+)\s*$", re.IGNORECASE | re.MULTILINE)
COPY = re.compile(r"^\s*COPY\s+(?!--from)(?:--\S+\s+)*(\S+)\s+(\S+)\s*$", re.IGNORECASE)

# The deployment recipe, by its in-tree path. Both files, every runtime target (ADR 0057 §3).
RECIPE = ("infra/docker-compose.prod.yml", "infra/.env.example")
# Forbidden destinations, with the reason each is forbidden. /work holds quarkus-app/, so a volume mounted
# there is seeded once and never refreshed — an upgrade that silently keeps running the first image's jar.
# /var/lib/veyq is the config-secret volume, which a mount likewise covers.
FORBIDDEN_DESTS = {
    "/work": "it holds quarkus-app/, and a volume mounted there freezes the app at its first image",
    "/var/lib/veyq": "it is the config-secret volume's mount point",
}

# A veyq image reference with a literal tag: `…/veyq:<tag>` or a bare `veyq:<tag>`. The character after `veyq`
# must be `:`, so a `veyq-<something>:<tag>` sibling is not claimed as the app image — live today for
# `veyq-admin:<tag>`, which the release recipe names beside the app and which is a DIFFERENT published image
# (the same discrimination validate_config_secret_persistence.py's APP_IMAGE makes, and for the same reason).
IMAGE_TAG = re.compile(r"(?:^|[\s/=\"'`])veyq:([^\s\"'`,;)}\]]+)")

# Anything that would make the version a function of the network rather than of the tree.
NETWORK_TOOLS = re.compile(r"(?:^|[\s|(&;])(gh|curl|wget|nc)\s", re.MULTILINE)


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
version_match = RELEASE_VERSION.match(version_value) if version_value else None

if version_text is None:
    fail(
        "there is no VERSION file at the repository root. It IS the release identity (ADR 0057 §1): without it "
        "the container workflow dies on its first command and nothing can ever be published, while "
        "infra/.env.example tells the operator to pin an image tag that has no producer."
    )
elif not version_match:
    fail(
        f"VERSION holds {version_value!r}, which is not `<major>.<minor>`. The pipeline emits `<major>`, "
        f"`<major>.<minor>`, `sha-<sha>` and `latest` — any other shape names an artifact with no producer."
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


def run_scenario(script: str, env: dict[str, str]) -> tuple[int, dict[str, str], str]:
    """Execute the workflow's own version script offline and read back what it wrote to $GITHUB_OUTPUT."""
    with tempfile.TemporaryDirectory(prefix="release_identity_") as tmp:
        work = pathlib.Path(tmp)
        (work / "VERSION").write_text(version_text or "")
        out = work / "github_output"
        out.touch()
        proc = subprocess.run(
            ["bash", "-c", script],
            cwd=work,
            env={"PATH": "/usr/bin:/bin", "GITHUB_OUTPUT": str(out), **env},
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
        scenarios = [
            (
                "a push to main",
                {"EVENT_NAME": "push", "REF_TYPE": "branch", "REF_NAME": "main"},
                0,
                {"version": version_value, "major": major, "publish_branch_tags": "true", "publish_latest": "true"},
            ),
            (
                "a pull request",
                {"EVENT_NAME": "pull_request", "REF_TYPE": "branch", "REF_NAME": "main"},
                0,
                {"version": version_value, "publish_branch_tags": "false", "publish_latest": "false"},
            ),
            (
                f"a push to release/{major}.x",
                {"EVENT_NAME": "push", "REF_TYPE": "branch", "REF_NAME": f"release/{major}.x"},
                0,
                {"version": version_value, "publish_branch_tags": "true", "publish_latest": "false"},
            ),
            (
                # The built-in bite: a ref that disagrees with the tree must not produce a version at all.
                f"a push to release/{int(major) + 7}.x, which disagrees with the tree",
                {"EVENT_NAME": "push", "REF_TYPE": "branch", "REF_NAME": f"release/{int(major) + 7}.x"},
                1,
                {},
            ),
        ] + [
            # Branches that are not release branches must publish nothing, because whatever publishes also
            # signs, and assertion 7 holds the documented verify to rejecting exactly these (ADR 0057 §3: what
            # signs and what verifies agree). `master` is the one a publish accepted until cr-image-provenance.
            (
                f"a push to {branch}, which is not a release branch",
                {"EVENT_NAME": "push", "REF_TYPE": "branch", "REF_NAME": branch},
                1,
                {},
            )
            for branch in non_release_branches(major)
        ]
        for label, env, expect_rc, expect in scenarios:
            try:
                rc, written, message = run_scenario(script, env)
            except subprocess.TimeoutExpired:
                fail(f"the `{VERSION_STEP}` step hung for {label} — a version derivation that waits is waiting on a network")
                continue
            if env["EVENT_NAME"] == "push" and rc == 0 and written.get("publish_branch_tags") == "true":
                publishing_branches.append(env["REF_NAME"])
            if expect_rc == 0 and rc != 0:
                fail(f"the `{VERSION_STEP}` step fails for {label}: {message or f'exit {rc}'}")
                continue
            if expect_rc != 0:
                if rc == 0:
                    fail(
                        f"the `{VERSION_STEP}` step accepts {label} and would stamp "
                        f"`{written.get('version')}` — a release names itself from the tree and publishes only "
                        f"from main or release/<major>.x, so a ref that disagrees with VERSION must fail rather "
                        f"than pick a side, and any other branch must fail rather than publish a signed image"
                    )
                continue
            for key, want in expect.items():
                if written.get(key) != want:
                    fail(
                        f"for {label} the `{VERSION_STEP}` step wrote `{key}={written.get(key)}`, expected "
                        f"`{key}={want}` (VERSION says {version_value})"
                    )

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

    for step_id, must_contain, why in (
        (
            REFUSE_STEP,
            ("exit 1",),
            "a publish whose tag already exists must FAIL rather than replace it: a transient registry error "
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


# ---- 4. the recipe travels in the image, and written-down tags name a shape the pipeline emits ---------------

def runtime_stages(body: str) -> dict[str, str]:
    """Every `runtime-*` stage's body, keyed by stage name."""
    stages = [(m.start(), m.group(1)) for m in STAGE.finditer(body)]
    out: dict[str, str] = {}
    for index, (offset, name) in enumerate(stages):
        end = stages[index + 1][0] if index + 1 < len(stages) else len(body)
        if name.startswith("runtime-"):
            out[name] = body[offset:end]
    return out


if not DOCKERFILE.is_file():
    fail("there is no Dockerfile — the image the recipe travels in does not exist")
else:
    stages = runtime_stages(DOCKERFILE.read_text())
    if not stages:
        fail("the Dockerfile declares no `runtime-*` stage — nothing to carry the deployment recipe")
    destinations: dict[str, set[str]] = {}
    for stage_name, stage_body in sorted(stages.items()):
        copied = {}
        for line in stage_body.splitlines():
            match = COPY.match(line)
            if match and match.group(1) in RECIPE:
                copied[match.group(1)] = match.group(2)
        for source in RECIPE:
            if not (ROOT / source).is_file():
                fail(f"`{source}` does not exist in the tree, so no runtime target can copy it into the image")
            elif source not in copied:
                fail(
                    f"the Dockerfile's `{stage_name}` target does not COPY `{source}` into the image. The "
                    f"recipe travels inside the image so it cannot drift from the bits it describes and "
                    f"inherits their cosign signature (ADR 0057 §3) — and it must be in EVERY runtime target, "
                    f"because a guard anchored on one passes a Dockerfile whose other one drifted."
                )
            else:
                dest = copied[source]
                destinations.setdefault(source, set()).add(dest)
                dest_dir = dest if dest.endswith("/") else dest.rsplit("/", 1)[0] + "/"
                for forbidden, reason in FORBIDDEN_DESTS.items():
                    if dest_dir == forbidden + "/" or dest_dir.startswith(forbidden + "/"):
                        fail(
                            f"the Dockerfile's `{stage_name}` target copies `{source}` to `{dest}`, under "
                            f"`{forbidden}` — {reason}. Put the recipe where nothing mounts and nothing writes."
                        )
    for source, dests in sorted(destinations.items()):
        if len(dests) > 1:
            fail(
                f"the runtime targets disagree about where `{source}` lands (" + ", ".join(sorted(dests)) +
                ") — a tool reading the recipe out of a published image cannot say which target it got"
            )

written_tags = 0
for path in sorted(p for p in INFRA.rglob("*") if p.is_file()):
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
                f"`<major>`, `<major>.<minor>`, `sha-<sha>` and `latest`; of those only `<major>.<minor>` "
                f"names a fixed set of bits, which is the only kind of tag worth writing down as the one an "
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

README = INFRA / "README.md"
CHART = ROOT / "charts/veyq/Chart.yaml"
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
# The registry the publish pushes to. The owner is not taken from the workflow — there it is
# `github.repository_owner`, which is whoever runs it, a fork included — but from the chart's `home`, the one
# file of state that names the upstream.
REGISTRY = "ghcr.io"
UPSTREAM = re.compile(r"^https://github\.com/([^/\s]+)/([^/\s]+?)/?$")
FENCE = re.compile(r"^```[^\n]*\n(.*?)^```", re.MULTILINE | re.DOTALL)


def verify_commands(markdown: str) -> list[list[str]]:
    """Every documented signature verification, as argv, from the fenced blocks of a markdown file."""
    commands = []
    for block in FENCE.findall(markdown):
        for line in block.replace("\\\n", " ").splitlines():
            line = line.split(" #", 1)[0].strip()
            if not line.startswith("cosign "):
                continue
            try:
                argv = shlex.split(line)
            except ValueError as e:
                fail(f"{README.relative_to(ROOT)}: cannot parse the command `{line[:80]}…`: {e}")
                continue
            if argv[:2] == ["cosign", "verify"]:
                commands.append(argv[2:])
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
elif not README.is_file():
    fail(f"{README.relative_to(ROOT)} is missing — it is where an operator is told how to verify a release")
else:
    owner, repo = upstream.group(1), upstream.group(2)
    workflow_file = WORKFLOW.relative_to(ROOT).as_posix()

    def san(ref: str, workflow: str = workflow_file, slug: str = f"{owner}/{repo}") -> str:
        """The certificate identity keyless signing from a GitHub workflow produces."""
        return f"https://github.com/{slug}/{workflow}@{ref}"

    accepted = {f"a publish from `{b}`": san(f"refs/heads/{b}") for b in publishing_branches}
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
        "a tag ref": san(f"refs/tags/v{version_value}"),
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

    commands = verify_commands(README.read_text())
    if not commands:
        fail(
            f"{README.relative_to(ROOT)} documents no `cosign verify`. Both images are keyless-signed by the "
            f"publish, and a signature nobody is told to check protects nobody (ADR 0057 §3). Document one per "
            f"published image, pinned to `{workflow_file}` in {owner}/{repo} on its release branches."
        )
    verified_images: set[str] = set()
    verify_summary = (
        f", and {len(commands)} documented `cosign verify` command(s) each accept the {len(accepted)} publishing "
        f"branch identities and reject {len(rejected)} others"
    )
    for argv in commands:
        shown = " ".join(argv)[:120]
        issuers = flag_values(argv, "--certificate-oidc-issuer")
        if flag_values(argv, "--certificate-oidc-issuer-regexp") or issuers != [OIDC_ISSUER]:
            fail(
                f"{README.relative_to(ROOT)}: `cosign verify {shown}` must pin exactly "
                f"`--certificate-oidc-issuer {OIDC_ISSUER}` — GitHub's OIDC issuer is what makes the identity "
                f"below mean `a GitHub workflow run`, and any other issuer lets someone else mint that string"
            )
        exact = flag_values(argv, "--certificate-identity")
        patterns = flag_values(argv, "--certificate-identity-regexp")
        if len(exact) + len(patterns) != 1:
            fail(
                f"{README.relative_to(ROOT)}: `cosign verify {shown}` must carry exactly one certificate "
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
                fail(f"{README.relative_to(ROOT)}: the identity pattern `{patterns[0]}` does not compile: {e}")
                continue
            matches = lambda subject, rx=compiled: rx.search(subject) is not None  # noqa: E731
        for what, subject in accepted.items():
            if not matches(subject):
                fail(
                    f"{README.relative_to(ROOT)}: `cosign verify {shown}` rejects {what} ({subject}) — the "
                    f"workflow publishes and signs from that branch, so a genuine release fails to verify"
                )
        for what, subject in rejected.items():
            if matches(subject):
                fail(
                    f"{README.relative_to(ROOT)}: `cosign verify {shown}` accepts {what} ({subject}). A "
                    f"verification is only as narrow as the identity it pins (ADR 0057 §3) — this one "
                    f"accepts a signature the veyq release never made."
                )
        for token in argv:
            match = re.match(rf"^{re.escape(REGISTRY)}/{re.escape(owner)}/([a-z0-9-]+)([@:])", token)
            if match:
                if match.group(2) == ":":
                    fail(
                        f"{README.relative_to(ROOT)}: `cosign verify {shown}` verifies a TAG. A tag can move "
                        f"between the verify and the pull; verify the digest the tag resolved to, and deploy "
                        f"that digest (ADR 0057 §3)."
                    )
                else:
                    verified_images.add(match.group(1))
    for image in sorted(published_images(WORKFLOW.read_text()) if WORKFLOW.is_file() else set()):
        if commands and image not in verified_images:
            fail(
                f"{README.relative_to(ROOT)} never verifies `{REGISTRY}/{owner}/{image}` by digest. The publish "
                f"signs every image it ships, and an operator told to verify one of a pair runs the other on "
                f"trust — and the pair includes the higher-privilege admin image (ADR 0065)."
            )
    if WORKFLOW.is_file() and "certificate-identity" in WORKFLOW.read_text():
        fail(
            f"{workflow_file} states a verification identity of its own. The identity is stated once, in "
            f"{README.relative_to(ROOT)}, where this guard holds it to what signs — a second copy is one "
            f"nothing checks, and the one the workflow carried accepted any workflow on any ref."
        )

if not written_tags and not errors:
    # A guard that checked nothing and a guard whose subject is sound report the same green.
    fail(
        f"no literal veyq image tag found anywhere under {INFRA.relative_to(ROOT)}/ — this half of the guard "
        f"checked NOTHING and would report green either way"
    )

if errors:
    print("RELEASE IDENTITY VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(
    f"release identity OK: VERSION {version_value} is derived offline by the publish workflow, a republish is "
    f"refused, ref v{version_value} is created, the recipe is declared in "
    f"{len(runtime_stages(DOCKERFILE.read_text()))} runtime target(s), {written_tags} written-down image "
    f"tag(s) name a shape the pipeline emits, and the production stack names its own project "
    f"(`name: {prod_project}`) with nothing in durable state instructing the flag it replaced{verify_summary}"
)
