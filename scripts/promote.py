#!/usr/bin/env python3
"""Promote a snapshot to a release: every check that decides it, and the refusal note (ADR 0091 decision 5).

`.github/workflows/release.yml` runs it. The owner saves a **draft** release in GitHub's release page on a
`snapshot-<major>.<minor>` tag, with the notes, and presses *Run workflow* on `release.yml` on the branch the snapshot
came from (`main`, or `release/<major>.x` for a fix of a previous major). The workflow's writes — the release tags, the
signatures, the chart, the git tag, the GitHub Release — stay in the workflow's own lines, where
`scripts/validate_release_identity.py` sees them; this file decides whether they may happen.

    python3 scripts/promote.py check --releases <jsonl> --ref <branch> --repository <owner/repo> \\
        --registry ghcr.io/<owner> --out <dir> [--unscheduled]
    python3 scripts/promote.py assets --dir <out>/v<version> --plan <dir>/plan.json
    python3 scripts/promote.py note --body <file> --reason <file> --run <url>      # prints the draft's new notes
    python3 scripts/promote.py release-identity                                    # veyqctl's issuer and identity

**check** refuses unless every one of these holds, in this order (each refusal names what it saw):

  1. **Exactly one draft release exists**, and its tag is `snapshot-<major>.<minor>`. Zero is nothing to promote; two
     or more would make the choice the workflow's.
  2. **The run's branch may release it**: `main` for the major `VERSION` holds there, `release/<major>.x` for its own.
  3. **The tag's commit is the snapshot it names**: the tree at that commit counts exactly `<major>.<minor>`
     (`release_assets.tree_release`, ADR 0057 §1), and the commit is on the run's branch.
  4. **It is newer than every release of its major** (no `v<major>.<m>` with `m ≥ minor`), so `<major>` and `latest`
     never move backwards: a release is named by its commit, and an older commit's is a rollback target that already
     exists or never should (ADR 0057 §1: "release a later commit"). `main` releases its own, highest major only, so
     `latest`, given from `main` alone, is always the newest release there is.
  5. **The snapshot is there and signed as one**: per image, `sha-<commit>` and `<version>-SNAPSHOT` name one index,
     and `cosign verify` accepts it under the SNAPSHOT identity (`ci.yml` of this repository on `main` or
     `release/<major>.x`).
  6. **No release name is taken by anything else**: `<version>` on an image is absent or already that index (a
     promotion that failed half-way and runs again); the chart's `<version>` exists only where the images' do.
  7. **Every lane attested a pass**: for every lane that exists (L1) and every architecture of the `stack` matrix at
     the snapshot's commit, an attestation of `snapshot.PREDICATE_TYPE` verifies under the snapshot identity on the
     app and the admin index — this commit, `passed`, every shard of the matrix — and the digests it names are exactly
     what the index holds.
  8. **Its inputs still hold**: one asset set across every lane, equal to the set produced again now from the
     snapshot's commit (`release_assets.py`, with `--unscheduled` when asked, so that refusal comes here too); no
     input this promotion cannot compare (a predecessor, a backup list — lanes L2 and L3, whose rerun is #453's and
     #461's to build); and the tools image is stamped `<major>.<minor>` with the major entry the tree derives now.

It then writes `plan.json`: the draft, the commit, the version, the three index digests, the chart's state, the asset
set, the major entry, whether the release becomes `latest` (from `main` only — with check 4, the newest release of the
highest major) and the names each image gets. On a refusal it writes `refusal.txt`, the sentence the `refused` job
appends to the draft's notes, and exits 1.

**assets** holds a produced release to the plan: its asset-set hash is the attested one, its manifest names each
image as `<registry>/<image>:<version>@<the planned digest>`, and its `majorEntry` is the one the tools image bakes.

Its limit: it reads the registry through Docker Buildx and cosign, and only `scripts/snapshot.py`'s "not found"
wording reads as absent — any other failure refuses. What `cosign verify-attestation` prints is parsed in both shapes
cosign has printed (a DSSE envelope with a base64 `payload`, or the statement itself); a shape it does not know is no
attestation, so the promotion refuses rather than passes. `scripts/promote_selftest.py` drives every refusal through a
fake registry and git.
"""
from __future__ import annotations  # `X | None` — the repo's scripts run on Python 3.9

import argparse
import base64
import dataclasses
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import snapshot  # noqa: E402 — the predicate type, the registry's read verdicts, an index's children

IMAGES = ("veyq", "veyq-admin", "veyq-tools")
LANE_IMAGES = ("veyq", "veyq-admin")  # the two images lane L1 installs, and so the two it attests on
CHART = "charts/veyq"
LANES = ("L1",)  # the lanes that exist; L2 and L3 join with #453 and #461
OIDC_ISSUER = "https://token.actions.githubusercontent.com"
SNAPSHOT_TAG = re.compile(r"^snapshot-(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
RELEASE_TAG = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
RELEASE_BRANCH = re.compile(r"^release/(0|[1-9][0-9]*)\.x$")
LANE_ORCHESTRATOR = "scripts/run_stack_e2e.py"


class Refused(Exception):
    """A refusal: the message is what the draft's notes and the log say. `drafts` are the ids to write it into."""

    def __init__(self, message: str, drafts: list[int] | None = None):
        super().__init__(message)
        self.drafts = drafts or []


def digest_of(raw: str) -> str:
    return "sha256:" + hashlib.sha256(raw.encode()).hexdigest()


def snapshot_identity(repository: str) -> str:
    """The certificate identity a SNAPSHOT is signed with: this repository's `ci.yml`, on a publishing branch."""
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise Refused(f"`{repository}` is not an `<owner>/<repository>`")
    return (rf"^https://github\.com/{re.escape(repository)}/\.github/workflows/ci\.yml"
            rf"@refs/heads/(main|release/[0-9]+\.x)$")


# ---- 1. the draft ----------------------------------------------------------------------------------------------

@dataclasses.dataclass
class Draft:
    id: int
    tag: str
    body: str


def choose_draft(releases: list[dict]) -> Draft:
    drafts = [r for r in releases if r.get("draft")]
    if not drafts:
        raise Refused("there is no draft release. Save one in the release page on the `snapshot-<major>.<minor>` tag of "
                      "the snapshot to release, with its notes, then run this workflow.")
    if len(drafts) > 1:
        tags = ", ".join(sorted(str(d.get("tag_name")) for d in drafts))
        raise Refused(f"{len(drafts)} draft releases exist ({tags}); a promotion releases exactly one. Delete the "
                      f"others and run this workflow again.", [int(d["id"]) for d in drafts])
    d = drafts[0]
    draft = Draft(int(d["id"]), str(d.get("tag_name") or ""), str(d.get("body") or ""))
    if not SNAPSHOT_TAG.match(draft.tag):
        raise Refused(f"the draft is on the tag `{draft.tag}`, not on a `snapshot-<major>.<minor>` tag — a release "
                      f"promotes a snapshot, and only a snapshot's tag names one. Pick the snapshot's tag in the "
                      f"draft.", [draft.id])
    return draft


def parse_snapshot_tag(tag: str) -> tuple[str, int, int]:
    m = SNAPSHOT_TAG.match(tag)
    if not m:
        raise Refused(f"`{tag}` is not `snapshot-<major>.<minor>`")
    return f"{m.group(1)}.{m.group(2)}", int(m.group(1)), int(m.group(2))


# ---- 2. the branch, 4. newer than every release -----------------------------------------------------------------

def check_branch(ref: str, ref_version_major: int, major: int) -> None:
    if ref == "main":
        if major != ref_version_major:
            raise Refused(f"`main` releases major {ref_version_major} (its VERSION), and this snapshot is of major "
                          f"{major} — a previous major's release is promoted from its `release/{major}.x` branch")
        return
    m = RELEASE_BRANCH.match(ref)
    if not m:
        raise Refused(f"this run is on `{ref}`; a release is promoted from `main` or `release/<major>.x`, the "
                      f"branches its signature names (Verify a release)")
    if int(m.group(1)) != major:
        raise Refused(f"`{ref}` releases major {m.group(1)}, and this snapshot is of major {major}")


def check_newer(release_tags: list[str], major: int, minor: int) -> None:
    taken = sorted((int(m.group(2)), t) for t in release_tags
                   if (m := RELEASE_TAG.match(t)) and int(m.group(1)) == major and int(m.group(2)) >= minor)
    if taken:
        raise Refused(f"`{taken[-1][1]}` exists already, so {major}.{minor} is not newer than every release of major "
                      f"{major} — `<major>` and `latest` never move backwards. Release a later snapshot.")


def release_names(version: str, major: int, latest: bool) -> list[str]:
    """The names a release gives the snapshot's index: `<major>.<minor>`, `<major>`, and `latest` from `main`."""
    return [version, str(major)] + (["latest"] if latest else [])


# ---- 7. attestations --------------------------------------------------------------------------------------------

def parse_attestations(stdout: str) -> list[dict]:
    """The in-toto statements `cosign verify-attestation` printed: a DSSE envelope per line (base64 `payload`), or the
    statement itself. Anything else is skipped — an unknown shape attests nothing."""
    statements = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            doc = json.loads(line)
        except ValueError:
            continue
        if isinstance(doc.get("payload"), str):
            try:
                doc = json.loads(base64.b64decode(doc["payload"]))
            except (ValueError, TypeError):
                continue
        elif isinstance(doc.get("dsseEnvelope"), dict) and isinstance(doc["dsseEnvelope"].get("payload"), str):
            try:
                doc = json.loads(base64.b64decode(doc["dsseEnvelope"]["payload"]))
            except (ValueError, TypeError):
                continue
        if isinstance(doc, dict) and isinstance(doc.get("predicate"), dict):
            statements.append(doc)
    return statements


def lane_verdicts(statements: list[dict], *, image: str, index_digest: str, commit: str, archs: list[str],
                  shards: list[str]) -> dict[tuple[str, str], tuple[str, str]]:
    """{(lane, arch): (asset set, the per-arch digest it installed)} for every lane × arch, from the statements about
    `image`'s index; refused on a missing or wrong one, naming each."""
    found: dict[tuple[str, str], tuple[str, str]] = {}
    problems: list[str] = []
    for lane in LANES:
        for arch in archs:
            candidates = [s for s in statements
                          if s.get("predicateType") == snapshot.PREDICATE_TYPE
                          and s["predicate"].get("lane") == lane and s["predicate"].get("arch") == arch]
            if not candidates:
                problems.append(f"no {lane} attestation for {arch}")
                continue
            why = []
            for s in candidates:
                p = s["predicate"]
                subjects = {f"sha256:{(sub.get('digest') or {}).get('sha256')}" for sub in s.get("subject") or []}
                inputs = p.get("inputs") or {}
                extra = sorted(k for k, v in inputs.items() if k != "assetSet" and v is not None)
                if index_digest not in subjects:
                    why.append("its subject is another digest")
                elif p.get("outcome") != "passed":
                    why.append(f"its outcome is `{p.get('outcome')}`")
                elif p.get("commit") != commit:
                    why.append(f"it is of commit {p.get('commit')}")
                elif sorted(p.get("shards") or []) != sorted(shards):
                    why.append(f"its shards are {sorted(p.get('shards') or [])}, the lane runs {sorted(shards)}")
                elif not isinstance((p.get("images") or {}).get(image), str):
                    why.append(f"it names no {image} digest")
                elif extra:
                    why.append(f"it records inputs this promotion cannot compare yet ({', '.join(extra)}) — the rerun "
                               f"of a lane whose inputs moved is not built (#453, #461)")
                elif not isinstance(inputs.get("assetSet"), str):
                    why.append("it records no asset set")
                else:
                    found[(lane, arch)] = (inputs["assetSet"], p["images"][image])
                    break
            else:
                problems.append(f"the {lane} attestation for {arch} does not hold: {'; '.join(why)}")
    if problems:
        raise Refused(f"{image}'s snapshot is not attested as tested: " + "; ".join(problems))
    return found


def check_index_covered(image: str, children: set[str], verdicts: dict, expand) -> None:
    """The index is exactly the per-arch builds the lanes attested: nothing in it that no lane installed."""
    covered: set[str] = set()
    for _, per_arch in verdicts.values():
        covered |= expand(per_arch)
    if covered != children:
        raise Refused(f"{image}'s snapshot index holds {sorted(children - covered) or 'nothing'} that no attested "
                      f"build stands for, and misses {sorted(covered - children) or 'nothing'} that one does")


# ---- the check, against a host ------------------------------------------------------------------------------------

@dataclasses.dataclass
class Plan:
    release_id: int
    tag: str
    commit: str
    version: str
    major: int
    latest: bool
    names: list[str]
    digests: dict[str, str]
    chart: str  # absent | present (a half-done promotion of this snapshot pushed it already)
    asset_set: str
    major_entry: str | None
    unscheduled: bool


def check(host, *, releases: list[dict], ref: str, repository: str, registry: str, unscheduled: bool,
          on_draft=lambda draft: None) -> Plan:
    draft = choose_draft(releases)
    on_draft(draft)
    try:
        return _check(host, draft, ref=ref, repository=repository, registry=registry, unscheduled=unscheduled)
    except Refused as e:
        raise Refused(str(e), e.drafts or [draft.id])


def _check(host, draft: Draft, *, ref: str, repository: str, registry: str, unscheduled: bool) -> Plan:
    version, major, minor = parse_snapshot_tag(draft.tag)
    check_branch(ref, host.ref_major(), major)
    commit = host.tag_commit(draft.tag)
    if not commit:
        raise Refused(f"the tag `{draft.tag}` names no commit in this repository")
    counted = host.tree_release(commit)
    if counted != version:
        raise Refused(f"the tag `{draft.tag}` is on {commit}, whose tree counts {counted} — a snapshot tag names the "
                      f"version of its own commit (ADR 0057 §1), so this one was moved or set by hand")
    if not host.is_ancestor(commit):
        raise Refused(f"{commit} is not on `{ref}`; a release is promoted on the branch its snapshot was published from")
    check_newer(host.release_tags(), major, minor)

    identity = snapshot_identity(repository)
    digests: dict[str, str] = {}
    children: dict[str, set[str]] = {}
    resumed = []
    for image in IMAGES:
        by_sha = host.inspect(f"{registry}/{image}:sha-{commit}")
        by_name = host.inspect(f"{registry}/{image}:{version}-SNAPSHOT")
        if by_sha is None or by_name is None:
            raise Refused(f"{registry}/{image} has no snapshot of {commit} (`sha-{commit}` "
                          f"{'absent' if by_sha is None else 'present'}, `{version}-SNAPSHOT` "
                          f"{'absent' if by_name is None else 'present'}) — a snapshot is published by ci.yml's weekly "
                          f"schedule or a dispatch of it")
        if digest_of(by_sha) != digest_of(by_name):
            raise Refused(f"{registry}/{image}: `sha-{commit}` and `{version}-SNAPSHOT` name two different indexes")
        digests[image] = digest_of(by_sha)
        children[image] = snapshot.children(by_sha)
        taken = host.inspect(f"{registry}/{image}:{version}")
        if taken is not None:
            if digest_of(taken) != digests[image]:
                raise Refused(f"{registry}/{image}:{version} already names {digest_of(taken)}, not this snapshot — a "
                              f"release name is never replaced")
            resumed.append(image)
        if not host.verify_signature(f"{registry}/{image}@{digests[image]}", identity):
            raise Refused(f"{registry}/{image}@{digests[image]} does not verify as a snapshot of this repository "
                          f"(`ci.yml` on a publishing branch)")
    chart = "absent" if host.inspect(f"{registry}/{CHART}:{version}") is None else "present"
    if chart == "present" and len(resumed) != len(IMAGES):
        raise Refused(f"{registry}/{CHART}:{version} exists while the images' `{version}` is not this snapshot's — "
                      f"the chart is another publish's")

    archs, shards = host.stack_matrix(commit)
    if not archs or not shards:
        raise Refused(f"the `stack` matrix at {commit} names no architecture or no shard")
    asset_sets: set[str] = set()
    for image in LANE_IMAGES:
        statements = parse_attestations(host.attestations(f"{registry}/{image}@{digests[image]}", identity))
        expand = lambda d, image=image: snapshot.children(host.inspect(f"{registry}/{image}@{d}") or "{}")  # noqa: E731
        verdicts = lane_verdicts(statements, image=image, index_digest=digests[image], commit=commit, archs=archs,
                                 shards=shards)
        check_index_covered(image, children[image], verdicts, expand)
        asset_sets |= {asset_set for asset_set, _ in verdicts.values()}
    if len(asset_sets) != 1:
        raise Refused(f"the lanes attest {len(asset_sets)} different asset sets: {sorted(asset_sets)}")
    attested = asset_sets.pop()
    produced = host.produce_asset_set(commit, unscheduled)
    if produced != attested:
        raise Refused(f"the release files produced from {commit} now hash to {produced}, the lanes installed "
                      f"{attested} — the published files would not be the tested ones")

    stamp = host.tools_release(f"{registry}/veyq-tools@{digests['veyq-tools']}")
    if stamp.get("release") != version:
        raise Refused(f"the tools image is stamped `{stamp.get('release')}`, not `{version}` — a snapshot from before "
                      f"a release could promote one (its stamp still carried `-SNAPSHOT`)")
    entry = host.major_entry(commit, version)
    if stamp.get("majorEntry") != entry:
        raise Refused(f"the tools image bakes `{stamp.get('majorEntry')}` as the release major {major} is entered from, "
                      f"and the tags now say `{entry}` — a release published since moved it; it is baked into the "
                      f"image, so release a snapshot built after it (ADR 0085 decision 10)")
    latest = ref == "main"
    return Plan(draft.id, draft.tag, commit, version, major, latest, release_names(version, major, latest), digests,
                chart, attested, entry, unscheduled)


# ---- assets, notes ----------------------------------------------------------------------------------------------

def check_assets(dest: pathlib.Path, plan: dict, registry: str) -> None:
    import release_assets  # this checkout's hash, the definition the lanes used

    got = release_assets.asset_set_digest(dest)
    if got != plan["asset_set"]:
        raise Refused(f"the release files hash to {got}, the lanes installed {plan['asset_set']}")
    manifest = json.loads((dest / release_assets.MANIFEST).read_text())
    want = {"app": "veyq", "admin": "veyq-admin", "tools": "veyq-tools"}
    for role, image in want.items():
        ref = f"{registry}/{image}:{plan['version']}@{plan['digests'][image]}"
        if manifest.get("images", {}).get(role) != ref:
            raise Refused(f"the manifest names {role} as `{manifest.get('images', {}).get(role)}`, not `{ref}`")
    if manifest.get("majorEntry") != plan["major_entry"]:
        raise Refused(f"the manifest's majorEntry is `{manifest.get('majorEntry')}`, the tools image bakes "
                      f"`{plan['major_entry']}`")
    if manifest.get("release") != plan["version"] or bool(manifest.get("unscheduled")) != plan["unscheduled"]:
        raise Refused("the manifest names another release or another `unscheduled` than the plan")


NOTE_MARK = "<!-- veyq: promotion refused -->"


def note(body: str, reason: str, run: str) -> str:
    """The owner's notes, kept, with the refusal appended under a marker — so each refusal adds one, and nothing of
    the owner's text is lost."""
    reason = " ".join(reason.split()) or "the promotion failed; the run's log says where"
    return f"{body.rstrip()}\n\n{NOTE_MARK}\n> **Promotion refused** ([run]({run})): {reason}\n"


# ---- the host: git, the registry, cosign, the producer -------------------------------------------------------------

class Host:
    def __init__(self, registry: str):
        self.registry = registry
        self.worktrees: dict[str, pathlib.Path] = {}
        self.tmp = pathlib.Path(tempfile.mkdtemp(prefix="promote-"))

    def _run(self, *args: str, check: bool = True, cwd: pathlib.Path = ROOT) -> subprocess.CompletedProcess:
        proc = subprocess.run(list(args), cwd=cwd, capture_output=True, text=True)
        if check and proc.returncode != 0:
            raise Refused(f"`{' '.join(args[:4])}` failed: {(proc.stderr or proc.stdout).strip()[-400:]}")
        return proc

    def ref_major(self) -> int:
        return int((ROOT / "VERSION").read_text().strip())

    def tag_commit(self, tag: str) -> str | None:
        proc = self._run("git", "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}^{{commit}}", check=False)
        return proc.stdout.strip() or None

    def worktree(self, commit: str) -> pathlib.Path:
        if commit not in self.worktrees:
            path = self.tmp / commit[:12]
            self._run("git", "worktree", "add", "--detach", str(path), commit)
            self.worktrees[commit] = path
        return self.worktrees[commit]

    def tree_release(self, commit: str) -> str:
        import release_assets
        try:
            return release_assets.tree_release(self.worktree(commit))
        except release_assets.ReleaseError as e:
            raise Refused(f"the tree at {commit} counts no release: {e}")

    def is_ancestor(self, commit: str) -> bool:
        return self._run("git", "merge-base", "--is-ancestor", commit, "HEAD", check=False).returncode == 0

    def release_tags(self) -> list[str]:
        return self._run("git", "tag", "--list", "v*").stdout.split()

    def inspect(self, ref: str) -> str | None:
        proc = self._run("docker", "buildx", "imagetools", "inspect", "--raw", ref, check=False)
        try:
            return snapshot.read_state(proc.returncode, proc.stdout, proc.stderr)
        except snapshot.Refused as e:
            raise Refused(str(e))

    def verify_signature(self, ref: str, identity: str) -> bool:
        return self._run("cosign", "verify", ref, "--certificate-oidc-issuer", OIDC_ISSUER,
                         "--certificate-identity-regexp", identity, check=False).returncode == 0

    def attestations(self, ref: str, identity: str) -> str:
        proc = self._run("cosign", "verify-attestation", "--type", snapshot.PREDICATE_TYPE, ref,
                         "--certificate-oidc-issuer", OIDC_ISSUER, "--certificate-identity-regexp", identity,
                         check=False)
        if proc.returncode != 0:
            raise Refused(f"no attestation of {ref} verifies as the snapshot's: {(proc.stderr or '').strip()[-400:]}")
        return proc.stdout

    def stack_matrix(self, commit: str) -> tuple[list[str], list[str]]:
        import yaml
        doc = yaml.safe_load(self._run("git", "show", f"{commit}:.github/workflows/ci.yml").stdout) or {}
        for job in (doc.get("jobs") or {}).values():
            if isinstance(job, dict) and any(LANE_ORCHESTRATOR in str(s.get("run") or "")
                                             for s in job.get("steps") or [] if isinstance(s, dict)):
                matrix = (job.get("strategy") or {}).get("matrix") or {}
                return sorted(map(str, matrix.get("arch") or [])), sorted(map(str, matrix.get("domain") or []))
        return [], []

    def produce_asset_set(self, commit: str, unscheduled: bool) -> str:
        import release_assets
        tree = self.worktree(commit)
        out = self.tmp / f"produced-{commit[:12]}"
        args = [sys.executable, str(tree / "scripts" / "release_assets.py"), str(out), "--app", "veyq:promotion",
                "--admin", "veyq-admin:promotion", "--tools", "veyq-tools:promotion"]
        proc = self._run(*args, *(["--unscheduled"] if unscheduled else []), check=False, cwd=tree)
        if proc.returncode != 0:
            raise Refused(f"the release files cannot be produced from {commit}: {(proc.stderr or '').strip()[-400:]}")
        tags = [p for p in out.iterdir() if p.is_dir()]
        return release_assets.asset_set_digest(tags[0])

    def tools_release(self, ref: str) -> dict:
        self._run("docker", "pull", "--quiet", ref)
        proc = self._run("docker", "run", "--rm", "--entrypoint", "cat", ref, "/opt/veyq/release.json")
        try:
            return json.loads(proc.stdout)
        except ValueError:
            raise Refused(f"the tools image's /opt/veyq/release.json is not JSON: {proc.stdout[:200]}")

    def major_entry(self, commit: str, version: str) -> str | None:
        tree = self.worktree(commit)
        proc = self._run(sys.executable, str(tree / "scripts" / "release_assets.py"), "--major-entry", version, cwd=tree)
        return proc.stdout.strip() or None

    def close(self) -> None:
        for path in self.worktrees.values():
            self._run("git", "worktree", "remove", "--force", str(path), check=False)


# ---- command line -------------------------------------------------------------------------------------------------

def read_releases(path: pathlib.Path) -> list[dict]:
    """`gh api --paginate repos/<repo>/releases --jq '.[]'`: one release per line."""
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def write_output(**values: object) -> None:
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as handle:
            for key, value in values.items():
                handle.write(f"{key}={value}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check")
    c.add_argument("--releases", required=True, type=pathlib.Path)
    c.add_argument("--ref", required=True)
    c.add_argument("--repository", required=True)
    c.add_argument("--registry", required=True)
    c.add_argument("--out", required=True, type=pathlib.Path)
    c.add_argument("--unscheduled", action="store_true")
    a = sub.add_parser("assets")
    a.add_argument("--dir", required=True, type=pathlib.Path)
    a.add_argument("--plan", required=True, type=pathlib.Path)
    a.add_argument("--registry", required=True)
    n = sub.add_parser("note")
    n.add_argument("--body", required=True, type=pathlib.Path)
    n.add_argument("--reason", required=True, type=pathlib.Path)
    n.add_argument("--run", required=True)
    sub.add_parser("release-identity")
    args = parser.parse_args()

    if args.command == "release-identity":
        import ast
        found = {}
        for node in ast.parse((ROOT / "src" / "tools" / "veyqctl.py").read_text()).body:
            if (isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                    and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str)):
                found[node.targets[0].id] = node.value.value
        print(f"issuer={found['COSIGN_OIDC_ISSUER']}")
        print(f"identity={found['COSIGN_IDENTITY_REGEXP']}")
        write_output(issuer=found["COSIGN_OIDC_ISSUER"], identity=found["COSIGN_IDENTITY_REGEXP"])
        return 0
    if args.command == "note":
        reason = args.reason.read_text() if args.reason.is_file() else ""
        sys.stdout.write(note(args.body.read_text(), reason, args.run))
        return 0
    if args.command == "assets":
        try:
            check_assets(args.dir, json.loads(args.plan.read_text()), args.registry)
        except Refused as e:
            print(f"promote: {e}", file=sys.stderr)
            return 1
        print(f"release files: the attested asset set, the planned digests and major entry")
        return 0

    args.out.mkdir(parents=True, exist_ok=True)

    def remember(draft: Draft) -> None:
        (args.out / "draft.json").write_text(json.dumps(dataclasses.asdict(draft)) + "\n")

    host = Host(args.registry)
    try:
        plan = check(host, releases=read_releases(args.releases), ref=args.ref, repository=args.repository,
                     registry=args.registry, unscheduled=args.unscheduled, on_draft=remember)
    except Refused as e:
        print(f"promote: REFUSED — {e}", file=sys.stderr)
        (args.out / "refusal.txt").write_text(str(e) + "\n")
        (args.out / "notify.json").write_text(json.dumps(e.drafts) + "\n")
        return 1
    finally:
        host.close()
    (args.out / "plan.json").write_text(json.dumps(dataclasses.asdict(plan), indent=2) + "\n")
    (args.out / "notify.json").write_text(json.dumps([plan.release_id]) + "\n")
    print(f"promote: {plan.tag} ({plan.commit}) may become v{plan.version}: names {', '.join(plan.names)}, "
          f"latest {plan.latest}, asset set {plan.asset_set}")
    write_output(version=plan.version, major=plan.major, commit=plan.commit, release_id=plan.release_id,
                 latest=str(plan.latest).lower(), chart=plan.chart)
    return 0


if __name__ == "__main__":
    sys.exit(main())
