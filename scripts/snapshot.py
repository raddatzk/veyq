#!/usr/bin/env python3
"""The decisions of a snapshot's publish (ADR 0091 decision 5): what each lane leg ran, which Checks run a snapshot
is made from and that its staged bytes are the ones that run tested, the attestation per lane and architecture, and
whether the snapshot's names are still free. `checks.yml`'s `stack` and publish.yml's `checks-run` and `snapshot` jobs
run it; the writes themselves — `imagetools create`, `cosign`, `helm push`, the tag — stay in the workflow, where
`scripts/validate_release_identity.py` sees and gates them.

    python3 scripts/snapshot.py lane-result --lane L1 --arch amd64 --shard memories --commit <sha> \\
        --asset-set sha256:<hex> --image veyq=sha256:<hex> --image veyq-admin=sha256:<hex> --out <file>
    python3 scripts/snapshot.py predicates --lane L1 --arch amd64 --arch arm64 --shard account … \\
        --commit <sha> --run <url> --results <dir> --out <dir>
    python3 scripts/snapshot.py check-unpublished --registry ghcr.io/<owner> --version <v> --commit <sha> \\
        --digests <dir> --image veyq --image veyq-admin --image veyq-tools --chart charts/veyq
    python3 scripts/snapshot.py checks-run --repository <owner>/<repo> --commit <HEAD sha> --branch <branch> \
        [--wait-minutes <n>]
    python3 scripts/snapshot.py staged --registry ghcr.io/<owner> --commit <sha> --digests <dir> --results <dir>

**checks-run** — which run a snapshot is made from (ADR 0091 decision 5, amended 2026-11-07: Publish runs no pipeline,
it signs a Checks run's staging). The branch must be `main` or `release/<major>.x` of the tree's major. It resolves the
**publishable commit** C (amended 2026-11-08): HEAD when HEAD has a `push` run of `.github/workflows/checks.yml` on that
branch; otherwise the newest first-parent ancestor that has one, accepted only if every commit after it up to HEAD
changes nothing but paths checks.yml's own `on.push.paths-ignore` ignores (read from the checkout, matched as GitHub
matches them) — a docs-only HEAD gets no Checks run of its own, and its tested ancestor's bytes are the ones it ships. A
commit without a run that changes another path is refused, named; so is a walk that finds no run within `WALK_BOUND`
commits. A C that already carries a `snapshot-` tag, or the `v<major>.<minor>` tag of its release, publishes nothing
(`kind=none`, a notice). Otherwise C's run is its
newest such run, refused, red and named, unless it is completed and `success`, and in its latest attempt the gate job
(`Pipeline green`), one lane L1 leg per architecture and domain of the commit's own `stack` matrix, and one lane L2 leg
per architecture and leg of its `stack-upgrade` matrix concluded `success`. A run cancelled by hand, still running, or red is refused with that reason; Publish never starts Checks
itself (owner 2026-11-07). With `--wait-minutes n` (the weekly schedule's, `await_completion`) a run still queued or
running is re-read every minute for up to n minutes first, and judged once it completed. Writes `kind`, `commit` (C — every later name of the publish is C's), `run_id`, `run_url`,
`attempt` and `version` (C's tree release, `release_assets.tree_release`) to `$GITHUB_OUTPUT`, and `draft_version`: the
version whose release draft the run writes — `version` for a new snapshot; for `kind=none` the version of C's existing
`snapshot-` tag while C carries no `v` tag (a dispatch refreshes the draft after a pull request's note was corrected),
else empty.

**staged** — that the bytes to be named are the ones that run tested: every image and architecture of the build has a
digest artifact from that run, every lane record names exactly those digests for its architecture, and every
`build-<commit>-<arch>` staging tag still resolves to its digest (a re-staged or moved tag is refused).

**lane-result** — one leg's record, written only after the leg is green: the lane, its architecture and shard (lane
L1's domain of the suite, lane L2's leg), the commit, the asset set it installed (`release_assets.py --asset-set`) and
the per-arch image digests it pulled; lane L2's also its `predecessor` (`--predecessor FILE`, which
`run_stack_upgrade.py --predecessor-out` writes): the release it upgraded from with the app and admin digests its
verified manifest named, or `null` for a leg that had none to start from — required of L2, refused of every other lane. A leg runs the commit's test code and holds no signing token; it hands this file over as an
artifact, and the `snapshot` job signs.

**predicates** — one in-toto predicate per lane and architecture (`PREDICATE_TYPE`), from the records of that lane in
the results directory. Refused unless every expected (architecture, shard) leg is there and none else, each `passed`, of
this commit and lane, with ONE asset set across all legs and one image digest per image within an architecture. Its
`inputs` are what decides whether the result still holds at a promotion: `assetSet`; `predecessor`, for lane L2 the
release each leg upgraded from (`{leg: {tag, images} | null}`, one per leg across the architectures, else refused),
`null` for every other lane; and `backups`, `null` until lane L3 (inbox #461).

**check-unpublished** — a snapshot is written once per commit and never replaced. Per image, `<version>-SNAPSHOT` and
`sha-<commit>` must be absent or name exactly the manifests this run's build pushed (a re-run of a half-done publish
continues); anything else is refused. A read that fails for any reason but "not found" is refused too: a transient
error read as "nothing published yet" is the silent republish ADR 0057 § Context describes. The chart is reported
`present` or `absent`, and refused when present while no image tag of this commit is — a chart without its images is
another run's. Writes `chart=<state>` and `chart_digest=<digest>` to `$GITHUB_OUTPUT` when it is set.

Its limits: what "not found" looks like is Docker Buildx's message, matched loosely (`NOT_FOUND`, a lower-case
`not found`, `MANIFEST_UNKNOWN`, `404`); a registry that words it otherwise is refused, never overwritten. The walk of
**checks-run** judges each commit by its own diff against its first parent, where GitHub's filter judges a push's whole
diff: stricter, never wider (a push of a code commit and a docs commit has its run on the docs one, its HEAD). It
matches `*` and `**` only, and refuses a pattern with any other glob syntax rather than guess how GitHub reads it.
`scripts/snapshot_selftest.py` holds the pure halves.
"""
from __future__ import annotations  # `X | None` — the repo's scripts run on Python 3.9

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import time

# The attestation every stack lane writes, per architecture; the promotion verifies it by this type. A name, not a URL
# anything fetches. `scripts/validate_release_identity.py` holds `publish.yml`'s attest step to it.
PREDICATE_TYPE = "https://veyq.app/attestations/stack-lane/v1"
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
LANES = ("L1", "L2", "L3")
# The lanes whose legs start from a release, and so record it (ADR 0091 decision 5's "the predecessor digest for L2").
PREDECESSOR_LANES = ("L2",)
RELEASE_TAG = re.compile(r"^v[0-9]+\.[0-9]+$")
INDEX_TYPES = ("application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json")
NOT_FOUND = re.compile(r"not.found|MANIFEST_UNKNOWN|\b404\b", re.IGNORECASE)


class Refused(Exception):
    """A refusal: the message is the whole explanation."""


# ---- lane results and predicates ----------------------------------------------------------------------------

def check_predecessor(predecessor: object, where: str) -> dict | None:
    """Lane L2's predecessor as a record carries it: None (the leg had none to start from, and installed nothing), or
    the starting release's tag and the app and admin digests its verified manifest named."""
    if predecessor is None:
        return None
    if not isinstance(predecessor, dict) or not RELEASE_TAG.match(str(predecessor.get("tag"))):
        raise Refused(f"{where}'s predecessor {predecessor!r} names no release tag (`v<major>.<minor>`)")
    images = predecessor.get("images")
    if not isinstance(images, dict) or sorted(images) != sorted(LANE_IMAGES) \
            or not all(isinstance(d, str) and DIGEST.match(d) for d in images.values()):
        raise Refused(f"{where}'s predecessor {predecessor.get('tag')} names {images!r}, not one digest of each of "
                      f"{', '.join(LANE_IMAGES)}")
    return {"tag": predecessor["tag"], "images": dict(sorted(images.items()))}


def lane_result(lane: str, arch: str, shard: str, commit: str, asset_set: str, images: dict[str, str],
                predecessor: object = None, has_predecessor: bool = False) -> dict:
    """One green leg's record. Lane L2's carries its `predecessor` (`has_predecessor`): the release it upgraded from,
    or None for a leg with no release to start from; no other lane has one."""
    if lane not in LANES:
        raise Refused(f"`{lane}` is not a lane ({', '.join(LANES)})")
    if has_predecessor != (lane in PREDECESSOR_LANES):
        raise Refused(f"lane {lane} records {'a' if has_predecessor else 'no'} predecessor; only "
                      f"{', '.join(PREDECESSOR_LANES)} records one, and always")
    if not COMMIT.match(commit):
        raise Refused(f"`{commit}` is not a full commit hash")
    if not DIGEST.match(asset_set):
        raise Refused(f"`{asset_set}` is not an asset-set hash (`sha256:<64 hex>`)")
    if not images:
        raise Refused("a leg that installed no image has nothing to attest")
    for name, digest in images.items():
        if not DIGEST.match(digest):
            raise Refused(f"the {name} digest `{digest}` is not `sha256:<64 hex>`")
    record = {"lane": lane, "arch": arch, "shard": shard, "outcome": "passed", "commit": commit,
              "assetSet": asset_set, "images": dict(sorted(images.items()))}
    if has_predecessor:
        record["predecessor"] = check_predecessor(predecessor, f"the {arch}/{shard} leg")
    return record


def predicates(results: list[dict], lane: str, archs: list[str], shards: list[str], commit: str,
               run: str) -> dict[str, dict]:
    """One predicate per architecture of `archs`, from the leg records; refused unless the records are exactly the
    expected legs, green, of this lane and commit, with one asset set and one digest per image per architecture."""
    if not archs or not shards:
        raise Refused("no architecture or no shard expected, so there is nothing to attest")
    expected = {(a, s) for a in archs for s in shards}
    seen: dict[tuple[str, str], dict] = {}
    for record in results:
        key = (str(record.get("arch")), str(record.get("shard")))
        if key in seen:
            raise Refused(f"two records for the {key[0]} leg of shard {key[1]}")
        if key not in expected:
            raise Refused(f"a record for the {key[0]} leg of shard {key[1]}, which the lane does not run")
        if record.get("lane") != lane or record.get("commit") != commit:
            raise Refused(f"the {key[0]}/{key[1]} record is of lane {record.get('lane')} at {record.get('commit')}, "
                          f"not {lane} at {commit}")
        if record.get("outcome") != "passed":
            raise Refused(f"the {key[0]}/{key[1]} record says `{record.get('outcome')}`")
        seen[key] = record
    missing = sorted(expected - set(seen))
    if missing:
        raise Refused("no record for " + ", ".join(f"{a}/{s}" for a, s in missing)
                      + " — a leg that left none did not pass")
    predecessors: dict[str, dict | None] | None = None
    if lane in PREDECESSOR_LANES:
        predecessors = {}
        for shard in shards:
            each = {json.dumps(check_predecessor(seen[(a, shard)].get("predecessor"), f"the {a}/{shard} record"),
                               sort_keys=True) for a in archs}
            if any("predecessor" not in seen[(a, shard)] for a in archs):
                raise Refused(f"a {shard} record of lane {lane} carries no predecessor (not even none)")
            if len(each) != 1:
                raise Refused(f"the {shard} legs started from different predecessors: {sorted(each)}")
            predecessors[shard] = json.loads(each.pop())
    else:
        carrying = sorted(f"{a}/{sh}" for (a, sh), r in seen.items() if r.get("predecessor") is not None)
        if carrying:
            raise Refused(f"lane {lane} has no predecessor, and the {', '.join(carrying)} record(s) name one")
    asset_sets = {r["assetSet"] for r in seen.values()}
    if len(asset_sets) != 1:
        raise Refused(f"the legs installed {len(asset_sets)} different asset sets ({', '.join(sorted(asset_sets))})")
    asset_set = next(iter(asset_sets))
    out = {}
    for arch in archs:
        legs = [seen[(arch, s)] for s in shards]
        images = {json.dumps(leg.get("images"), sort_keys=True) for leg in legs}
        if len(images) != 1:
            raise Refused(f"the {arch} legs installed different images: {sorted(images)}")
        out[arch] = {
            "lane": lane, "arch": arch, "outcome": "passed", "commit": commit, "run": run,
            "shards": sorted(shards), "images": legs[0]["images"],
            "inputs": {"assetSet": asset_set, "predecessor": predecessors, "backups": None},
        }
    return out


# ---- write-once -----------------------------------------------------------------------------------------------

def children(raw: str) -> set[str]:
    """The manifests an image reference stands for: an index's entries, or the manifest itself."""
    doc = json.loads(raw)
    if doc.get("mediaType") in INDEX_TYPES or "manifests" in doc:
        return {str(m.get("digest")) for m in doc.get("manifests") or []}
    return {"sha256:" + hashlib.sha256(raw.encode()).hexdigest()}


def read_state(returncode: int, stdout: str, stderr: str) -> str | None:
    """The raw manifest a tag names, None when the registry says it does not exist; refused on any other failure."""
    if returncode == 0:
        return stdout
    if NOT_FOUND.search(stderr or ""):
        return None
    raise Refused(f"the registry read failed with something other than `not found` ({(stderr or '').strip()[-300:]}) "
                  f"— read as `absent`, it would let this publish replace a name that exists")


def decide(image: str, tags: dict[str, str | None], built: set[str]) -> bool:
    """Whether `image`'s snapshot names already hold THIS build (True), are all free (False), or refuse."""
    held = False
    for tag, raw in tags.items():
        if raw is None:
            continue
        found = children(raw)
        if found != built:
            raise Refused(
                f"{image}:{tag} already names {sorted(found)}, not this build's {sorted(built)} — a snapshot is written "
                f"once per commit and never replaced (ADR 0091 decision 5). A second build of a commit that has a "
                f"snapshot is not published; a version that names another commit means two branches count the same "
                f"minor (ADR 0057 §1)."
            )
        held = True
    return held


def inspect(ref: str) -> str | None:
    proc = subprocess.run(["docker", "buildx", "imagetools", "inspect", "--raw", ref], capture_output=True, text=True)
    return read_state(proc.returncode, proc.stdout, proc.stderr)


def check_unpublished(registry: str, version: str, commit: str, digests: pathlib.Path, images: list[str],
                      chart: str) -> tuple[str, str]:
    held_any = False
    for image in images:
        built: set[str] = set()
        files = sorted(digests.glob(f"digest-{image}-*/{image}"))
        if not files:
            raise Refused(f"no digest of {image} from build in {digests}")
        for path in files:
            digest = path.read_text().strip()
            if not DIGEST.match(digest):
                raise Refused(f"{path} holds `{digest}`, not a digest")
            raw = inspect(f"{registry}/{image}@{digest}")
            if raw is None:
                raise Refused(f"{registry}/{image}@{digest}, which build pushed, is not in the registry")
            built |= children(raw)
        tags = {t: inspect(f"{registry}/{image}:{t}") for t in (f"{version}-SNAPSHOT", f"sha-{commit}")}
        held = decide(f"{registry}/{image}", tags, built)
        held_any = held_any or held
        print(f"{registry}/{image}: {'this build already holds its snapshot names' if held else 'its snapshot names are free'}")
    raw_chart = inspect(f"{registry}/{chart}:{version}-SNAPSHOT")
    if raw_chart is not None and not held_any:
        raise Refused(f"{registry}/{chart}:{version}-SNAPSHOT exists while no image of this commit names its snapshot — "
                      f"the chart is another run's")
    state = "absent" if raw_chart is None else "present"
    digest = "" if raw_chart is None else "sha256:" + hashlib.sha256(raw_chart.encode()).hexdigest()
    print(f"{registry}/{chart}:{version}-SNAPSHOT: {state}")
    return state, digest


# ---- the Checks run a snapshot is made from -------------------------------------------------------------------

CHECKS_WORKFLOW = ".github/workflows/checks.yml"
GATE_JOB_NAME = "Pipeline green"  # checks.yml's `green` job; validate_release_identity.py holds the name to it
LANE_JOB_NAME = "Stack lane L1 ({arch}, {domain})"  # checks.yml's `stack` job's name, per leg
UPGRADE_JOB_NAME = "Stack lane L2 ({arch}, {leg})"  # checks.yml's `stack-upgrade` job's name, per leg
UPGRADE_JOB = "stack-upgrade"
BUILD_IMAGES = ("veyq", "veyq-admin", "veyq-tools")  # checks.yml's build matrix; validate_release_identity.py holds it
LANE_IMAGES = ("veyq", "veyq-admin")  # what lane L1 installs, and so what each lane record names


def push_runs(runs: list[dict], commit: str, branch: str) -> list[dict]:
    """The `push` runs of checks.yml for exactly `commit` on `branch` (the API's list, filtered again here — a filter
    the API ignored must not hand over another commit's run)."""
    if not COMMIT.match(commit):
        raise Refused(f"`{commit}` is not a full commit hash")
    return [r for r in runs if r.get("head_sha") == commit and r.get("event") == "push"
            and r.get("head_branch") == branch and str(r.get("path") or "").split("@", 1)[0] == CHECKS_WORKFLOW]


def pick_run(runs: list[dict], commit: str, branch: str) -> dict:
    """A commit's Checks run: the newest `push` run of checks.yml for exactly `commit` on `branch`."""
    mine = push_runs(runs, commit, branch)
    if not mine:
        raise Refused(f"no push run of {CHECKS_WORKFLOW} for {commit} on {branch} — a snapshot is made only from the "
                      f"Checks run that staged it; push the commit (or re-run its Checks run) first")
    return max(mine, key=lambda r: (int(r.get("run_number") or 0), str(r.get("created_at") or "")))


# How far back a docs-only HEAD may reach for its tested ancestor: HEAD and at most this many commits before it, all
# first parents. A main that has gone this long without a Checks run is a question for a person, not for a walk.
WALK_BOUND = 50
# The filter-glob syntax this reader matches as GitHub does; any other (`?`, `+`, `[…]`, `{…}`, a leading `\`) is
# refused, never guessed — a pattern read too wide would let a code commit pass for docs.
UNREAD_GLOB = re.compile(r"[?+\[\]{}\\]")


def glob_regex(pattern: str) -> re.Pattern[str]:
    """A `paths-ignore` glob as GitHub matches it: `**/` any directories or none (so `**/*.md` takes a root `.md`),
    `**` anything, `*` anything within one path segment. validate_workflow_paths.py reads the same subset."""
    if UNREAD_GLOB.search(pattern):
        raise Refused(f"`{pattern}` in {CHECKS_WORKFLOW}'s push `paths-ignore` uses glob syntax this reader does not "
                      f"match (only `*` and `**`) — it cannot say which commits Checks skipped")
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out, i = out + "(?:.*/)?", i + 3
        elif pattern.startswith("**", i):
            out, i = out + ".*", i + 2
        elif pattern[i] == "*":
            out, i = out + "[^/]*", i + 1
        else:
            out, i = out + re.escape(pattern[i]), i + 1
    return re.compile(out + r"\Z")


def ignored_by(patterns: list[str]):
    """Whether Checks' push filter ignores a path: the last pattern that matches decides, a `!` one re-including it
    (GitHub's order rule for path filters)."""
    compiled = [(p.startswith("!"), glob_regex(p[1:] if p.startswith("!") else p)) for p in patterns]

    def ignored(path: str) -> bool:
        verdict = False
        for negated, rx in compiled:
            if rx.match(path):
                verdict = not negated
        return verdict
    return ignored


def push_paths_ignore(root: pathlib.Path) -> list[str]:
    """checks.yml's `on.push.paths-ignore`, from the checked-out file — never a copy."""
    import yaml
    doc = yaml.safe_load((root / CHECKS_WORKFLOW).read_text()) or {}
    on = doc.get("on", doc.get(True)) or {}  # PyYAML reads a bare `on:` key as the boolean True (YAML 1.1)
    push = on.get("push") if isinstance(on, dict) else None
    if not isinstance(push, dict):
        raise Refused(f"{CHECKS_WORKFLOW} has no `on.push` mapping, so no commit's missing Checks run can be explained")
    if "paths" in push:
        raise Refused(f"{CHECKS_WORKFLOW}'s push filter is `paths:`; this reads `paths-ignore` only")
    return [str(p) for p in push.get("paths-ignore") or []]


RELEASE_TAG = re.compile(r"^v[0-9]+\.[0-9]+$")


def published_by(tags: list[str]) -> str:
    """The tags among a commit's `tags` that make it published: its `snapshot-` tag, and its release tag
    `v<major>.<minor>` — release.yml deletes the snapshot tag once it has released the commit, so without the release
    tag the next Publish of an unchanged `main` made the released commit a snapshot again (`snapshot-1.247` on
    2026-11-16, two days after `v1.247`; cr-release-draft-from-publish)."""
    return " ".join(t for t in tags if t.startswith("snapshot-") or RELEASE_TAG.match(t))


SNAPSHOT_TAG = re.compile(r"^snapshot-([0-9]+\.[0-9]+)$")


def unreleased_snapshot(published: str) -> str:
    """The version of the one `snapshot-` tag among a published commit's tags, while no `v` tag says it was released —
    the snapshot whose release draft a run that publishes nothing still refreshes (a note corrected in its pull request
    after the snapshot; ADR 0091 decision 5, refined 2026-11-23). Empty for a released commit, and for anything not exactly one tag."""
    tags = published.split()
    if any(RELEASE_TAG.match(t) for t in tags):
        return ""
    versions = [m.group(1) for m in (SNAPSHOT_TAG.match(t) for t in tags) if m]
    return versions[0] if len(versions) == 1 else ""


def publishable(head: str, branch: str, chain: list[str], tags_of, runs_of, files_of, ignored,
                bound: int = WALK_BOUND) -> dict:
    """The commit a snapshot of `head` is made from, walking `chain` (`head` and its first parents, newest first):
    the first that already carries a `snapshot-` tag (`published`: nothing to publish) or has a Checks push run
    (`run`), provided every commit before it in the walk changes nothing but paths Checks' push filter ignores
    (`skipped`). Refused, naming the commit, when one with no run changes another path, and when none is found within
    `bound` commits. `tags_of(c)`, `runs_of(c)` (the API's runs for c) and `files_of(c)` (the paths c changes against
    its first parent, None for a root commit) are the world; `ignored(path)` is `ignored_by(…)`."""
    if not chain or chain[0] != head:
        raise Refused(f"the walk does not start at {head}")
    skipped: list[str] = []
    for commit in chain[:bound]:
        published = tags_of(commit)
        if published:
            return {"commit": commit, "published": published, "run": None, "skipped": skipped}
        mine = push_runs(runs_of(commit), commit, branch)
        if mine:
            run = max(mine, key=lambda r: (int(r.get("run_number") or 0), str(r.get("created_at") or "")))
            return {"commit": commit, "published": "", "run": run, "skipped": skipped}
        files = files_of(commit)
        if files is None:
            raise Refused(f"no push run of {CHECKS_WORKFLOW} for {commit} on {branch}, the first commit of the history "
                          f"— a snapshot is made only from the Checks run that staged it")
        others = [f for f in files if not ignored(f)]
        if others:
            where = "HEAD" if commit == head else f"between {head} (HEAD) and its newest tested ancestor"
            shown = ", ".join(others[:5]) + (f" and {len(others) - 5} more" if len(others) > 5 else "")
            raise Refused(f"no push run of {CHECKS_WORKFLOW} for {commit} on {branch} ({where}), and it changes "
                          f"{shown}, which Checks does not ignore — its bytes were never tested; push the commit (or "
                          f"re-run its Checks run) first")
        skipped.append(commit)
    raise Refused(f"no push run of {CHECKS_WORKFLOW} on {branch} for {head} or any of the {len(skipped) - 1} docs-only "
                  f"commits before it (the walk stops at {bound}) — re-run Checks on a commit, or push")


def await_completion(run: dict, fetch, wait_seconds: int, poll: int = 60, sleep=None, clock=None) -> dict:
    """`run` re-read through `fetch(run_id)` every `poll` seconds until it has completed, for at most `wait_seconds`;
    what it is then goes to `checks_verdict`, which still refuses a run not completed. 0 waits not at all (a dispatch:
    the owner is there to run it again). The weekly schedule waits, because GitHub starts it late and at no fixed time:
    on 2026-11-23 it ran at 12:56, not 03:17, minutes after a merge, and refused HEAD's `pending` Checks run (Publish run
    45179033821) — no snapshot and no draft for the week unless someone dispatched (cr-release-draft-from-publish)."""
    sleep, clock = sleep or time.sleep, clock or time.monotonic
    deadline = clock() + wait_seconds
    while run.get("status") != "completed" and clock() < deadline:
        print(f"Checks run {run.get('id')} ({run.get('html_url')}) is `{run.get('status')}`: waiting for it, at most "
              f"{max(0, round((deadline - clock()) / 60))} more minute(s)", flush=True)
        sleep(max(1, min(poll, deadline - clock())))
        run = fetch(run.get("id"))
    return run


def checks_verdict(run: dict, jobs: list[dict], archs: list[str], domains: list[str], legs: list[str]) -> dict:
    """Whether a snapshot may be made from `run` (pick_run's), given its latest attempt's jobs (`filter=latest`);
    refused, naming why not. Every lane L1 leg (`domains`) and every lane L2 leg (`legs`) of each architecture."""
    if not archs or not domains:
        raise Refused("the commit's `stack` matrix names no architecture or no domain, so no lane result can be checked")
    if not legs:
        raise Refused(f"the commit's `{UPGRADE_JOB}` matrix names no leg, so lane L2 cannot be checked")
    where = f"Checks run {run.get('id')} (attempt {run.get('run_attempt')}, {run.get('html_url')})"
    if run.get("status") != "completed":
        raise Refused(f"{where} is `{run.get('status')}`, not completed — publish again once it has finished")
    if run.get("conclusion") != "success":
        raise Refused(f"{where} concluded `{run.get('conclusion')}`, not `success` — a snapshot is made only from a "
                      f"wholly green run (a run cancelled by hand included: re-run it, or push again)")
    by_name: dict[str, list[dict]] = {}
    for job in jobs:
        by_name.setdefault(str(job.get("name")), []).append(job)
    attempts = {int(j.get("run_attempt") or 0) for j in jobs}
    if attempts and attempts != {int(run.get("run_attempt") or 0)}:
        raise Refused(f"{where}: its jobs are of attempts {sorted(attempts)}, not only its latest — read the latest")
    wanted = ([GATE_JOB_NAME] + [LANE_JOB_NAME.format(arch=a, domain=d) for a in archs for d in domains]
              + [UPGRADE_JOB_NAME.format(arch=a, leg=leg) for a in archs for leg in legs])
    for name in wanted:
        found = by_name.get(name) or []
        if len(found) != 1 or found[0].get("conclusion") != "success":
            state = "missing" if not found else ", ".join(str(j.get("conclusion")) for j in found)
            raise Refused(f"{where}: job `{name}` is {state}, not one `success` — every stage and every lane leg on "
                          f"both architectures must have passed in the run a snapshot is made from")
    return {"run_id": str(run.get("id")), "run_url": str(run.get("html_url")), "attempt": str(run.get("run_attempt"))}


def staged_verdict(digests: dict[tuple[str, str], str], records: list[dict], staged: dict[tuple[str, str], str | None],
                   archs: list[str]) -> None:
    """Refused unless the run's digest artifacts cover every image and architecture, every lane record names exactly
    those digests, and every staging tag still resolves to its digest."""
    for image in BUILD_IMAGES:
        for arch in archs:
            digest = digests.get((image, arch))
            if not digest or not DIGEST.match(digest):
                raise Refused(f"the Checks run left no digest of {image} ({arch}) — its build did not stage it")
            if staged.get((image, arch)) != digest:
                raise Refused(f"the staging tag of {image} ({arch}) resolves to {staged.get((image, arch))}, not the "
                              f"{digest} the Checks run staged and tested — a staged image was replaced; publish "
                              f"nothing from it")
    if not records:
        raise Refused("the Checks run left no lane record")
    for record in records:
        arch = str(record.get("arch"))
        want = {image: digests.get((image, arch)) for image in LANE_IMAGES}
        if record.get("images") != want:
            raise Refused(f"the {arch}/{record.get('shard')} lane record names {record.get('images')}, not the digests "
                          f"the Checks run staged ({want}) — the lane tested other bytes than the ones to be named")


def _gh_json(*args: str):
    proc = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    if proc.returncode != 0:
        raise Refused(f"`gh api {' '.join(args)}` failed: {(proc.stderr or proc.stdout).strip()[-300:]}")
    return json.loads(proc.stdout)


def stack_matrix(root: pathlib.Path) -> tuple[list[str], list[str]]:
    """The `stack` job's architectures and domains in the checked-out commit's checks.yml."""
    import yaml
    doc = yaml.safe_load((root / CHECKS_WORKFLOW).read_text()) or {}
    matrix = (((doc.get("jobs") or {}).get("stack") or {}).get("strategy") or {}).get("matrix") or {}
    return sorted(map(str, matrix.get("arch") or [])), sorted(map(str, matrix.get("domain") or []))


def upgrade_legs(root: pathlib.Path) -> list[str]:
    """Lane L2's legs in the checked-out commit's checks.yml (`stack-upgrade`'s matrix `leg`)."""
    import yaml
    doc = yaml.safe_load((root / CHECKS_WORKFLOW).read_text()) or {}
    matrix = (((doc.get("jobs") or {}).get(UPGRADE_JOB) or {}).get("strategy") or {}).get("matrix") or {}
    return sorted(map(str, matrix.get("leg") or []))


def _git_out(root: pathlib.Path, *args: str) -> str:
    proc = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True)
    if proc.returncode != 0:
        raise Refused(f"`git {' '.join(args)}` failed: {(proc.stderr or proc.stdout).strip()[-300:]}")
    return proc.stdout


def changed_files(root: pathlib.Path, commit: str) -> list[str] | None:
    """The paths `commit` changes against its first parent — a rename as both its paths — or None for a root commit."""
    if subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{commit}^1"], cwd=root,
                      capture_output=True).returncode != 0:
        return None
    return [p for p in _git_out(root, "diff", "--name-only", "--no-renames", "-z", f"{commit}^1", commit).split("\0") if p]


def checks_run(repository: str, head: str, branch: str, root: pathlib.Path, bound: int = WALK_BOUND,
               wait_seconds: int = 0) -> dict:
    sys.path.insert(0, str(root / "scripts"))
    import release_assets
    if not COMMIT.match(head):
        raise Refused(f"`{head}` is not a full commit hash")
    major = (root / "VERSION").read_text().strip()
    if branch not in ("main", f"release/{major}.x"):
        raise Refused(f"`{branch}` is no branch a snapshot is published from (VERSION {major}: main or release/{major}.x)")
    chain = _git_out(root, "rev-list", "--first-parent", f"--max-count={bound}", head).split()

    def tags_of(commit: str) -> str:
        return published_by(_git_out(root, "tag", "--points-at", commit).split())

    def runs_of(commit: str) -> list[dict]:
        return _gh_json(f"repos/{repository}/actions/workflows/checks.yml/runs?head_sha={commit}&event=push"
                        f"&branch={branch}&per_page=100").get("workflow_runs") or []

    found = publishable(head, branch, chain, tags_of, runs_of, lambda c: changed_files(root, c),
                        ignored_by(push_paths_ignore(root)), bound)
    commit = found["commit"]
    if found["skipped"]:
        print(f"::notice::HEAD {head} and the commit(s) before it down to {commit} — {' '.join(found['skipped'])} — "
              f"have no Checks push run and change only paths Checks ignores: the snapshot is made from {commit}, "
              f"the newest tested commit, and names it")
    if found["published"]:
        print(f"::notice::{commit} already carries its snapshot ({found['published']}): nothing to publish")
        return {"kind": "none", "commit": commit, "run_id": "", "run_url": "", "attempt": "", "version": "",
                "draft_version": unreleased_snapshot(found["published"])}
    # The matrix is HEAD's checks.yml, and C's: the walk accepts no commit that changes it (it is no ignored path).
    archs, domains = stack_matrix(root)
    run = await_completion(found["run"], lambda run_id: _gh_json(f"repos/{repository}/actions/runs/{run_id}"),
                           wait_seconds)
    jobs: list[dict] = []
    page = 1
    while True:
        chunk = _gh_json(f"repos/{repository}/actions/runs/{run.get('id')}/jobs?filter=latest&per_page=100&page={page}")
        jobs.extend(chunk.get("jobs") or [])
        if len(chunk.get("jobs") or []) < 100:
            break
        page += 1
    verdict = checks_verdict(run, jobs, archs, domains, upgrade_legs(root))
    version = release_assets.tree_release(root, commit)
    print(f"publishing {version} of {commit} from {verdict['run_url']} (attempt {verdict['attempt']})")
    return {"kind": "snapshot", "commit": commit, **verdict, "version": version, "draft_version": version}


def staged(registry: str, commit: str, digests_dir: pathlib.Path, results_dir: pathlib.Path,
           archs: list[str]) -> None:
    digests = {}
    for path in digests_dir.glob("digest-*-*/*"):
        image = path.name
        arch = path.parent.name[len(f"digest-{image}-"):]
        digests[(image, arch)] = path.read_text().strip()
    records = [json.loads(p.read_text()) for p in sorted(results_dir.rglob("*.json"))]
    tags = {}
    for image in BUILD_IMAGES:
        for arch in archs:
            raw = inspect(f"{registry}/{image}:build-{commit}-{arch}")
            tags[(image, arch)] = None if raw is None else "sha256:" + hashlib.sha256(raw.encode()).hexdigest()
    staged_verdict(digests, records, tags, archs)
    print(f"the {len(digests)} staged image(s) and {len(records)} lane record(s) are the Checks run's bytes")


# ---- command line -----------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    r = sub.add_parser("lane-result")
    r.add_argument("--lane", required=True)
    r.add_argument("--arch", required=True)
    r.add_argument("--shard", required=True)
    r.add_argument("--commit", required=True)
    r.add_argument("--asset-set", required=True)
    r.add_argument("--image", action="append", default=[], metavar="NAME=DIGEST")
    r.add_argument("--predecessor", type=pathlib.Path, default=None, metavar="FILE",
                   help="lane L2: the JSON `run_stack_upgrade.py --predecessor-out` wrote (the release, or null)")
    r.add_argument("--out", required=True, type=pathlib.Path)
    p = sub.add_parser("predicates")
    p.add_argument("--lane", required=True)
    p.add_argument("--arch", action="append", default=[])
    p.add_argument("--shard", action="append", default=[])
    p.add_argument("--commit", required=True)
    p.add_argument("--run", required=True)
    p.add_argument("--results", required=True, type=pathlib.Path)
    p.add_argument("--out", required=True, type=pathlib.Path)
    c = sub.add_parser("check-unpublished")
    c.add_argument("--registry", required=True)
    c.add_argument("--version", required=True)
    c.add_argument("--commit", required=True)
    c.add_argument("--digests", required=True, type=pathlib.Path)
    c.add_argument("--image", action="append", default=[])
    c.add_argument("--chart", required=True)
    k = sub.add_parser("checks-run")
    k.add_argument("--repository", required=True)
    k.add_argument("--commit", required=True)
    k.add_argument("--branch", required=True)
    k.add_argument("--wait-minutes", type=int, default=0)
    s = sub.add_parser("staged")
    s.add_argument("--registry", required=True)
    s.add_argument("--commit", required=True)
    s.add_argument("--digests", required=True, type=pathlib.Path)
    s.add_argument("--results", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        if args.command == "lane-result":
            images = dict(item.split("=", 1) for item in args.image if "=" in item)
            if len(images) != len(args.image):
                raise Refused(f"--image takes NAME=DIGEST, got {args.image}")
            predecessor = json.loads(args.predecessor.read_text()) if args.predecessor is not None else None
            record = lane_result(args.lane, args.arch, args.shard, args.commit, args.asset_set, images, predecessor,
                                 has_predecessor=args.predecessor is not None)
            args.out.parent.mkdir(parents=True, exist_ok=True)
            args.out.write_text(json.dumps(record, indent=2) + "\n")
            print(f"{args.out}: {args.lane} {args.arch}/{args.shard} passed")
        elif args.command == "predicates":
            # Every lane's records arrive in one directory; each lane's predicate reads its own.
            records = [r for r in (json.loads(path.read_text()) for path in sorted(args.results.rglob("*.json")))
                       if r.get("lane") == args.lane]
            out = predicates(records, args.lane, args.arch, args.shard, args.commit, args.run)
            args.out.mkdir(parents=True, exist_ok=True)
            for arch, predicate in out.items():
                (args.out / f"{args.lane}-{arch}.json").write_text(json.dumps(predicate, indent=2) + "\n")
                print(f"{args.lane} {arch}: {len(predicate['shards'])} shard(s), asset set {predicate['inputs']['assetSet']}")
        elif args.command == "checks-run":
            root = pathlib.Path(__file__).resolve().parent.parent
            found = checks_run(args.repository, args.commit, args.branch, root, wait_seconds=60 * args.wait_minutes)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as handle:
                    handle.write("".join(f"{k}={v}\n" for k, v in found.items()))
        elif args.command == "staged":
            root = pathlib.Path(__file__).resolve().parent.parent
            archs, _ = stack_matrix(root)
            staged(args.registry, args.commit, args.digests, args.results, archs)
        else:
            if not COMMIT.match(args.commit) or not re.fullmatch(r"[0-9]+\.[0-9]+", args.version):
                raise Refused(f"`{args.version}` / `{args.commit}` is not a version and a full commit hash")
            state, digest = check_unpublished(args.registry, args.version, args.commit, args.digests, args.image,
                                              args.chart)
            if os.environ.get("GITHUB_OUTPUT"):
                with open(os.environ["GITHUB_OUTPUT"], "a") as handle:
                    handle.write(f"chart={state}\nchart_digest={digest}\n")
    except Refused as e:
        print(f"snapshot: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
