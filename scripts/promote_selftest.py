#!/usr/bin/env python3
"""Self-test of a promotion's decisions (`scripts/promote.py`, ADR 0091 decision 5), offline: a fake git, registry,
cosign and producer, one world in which the promotion holds, and one forged world per refusal — every refusal
`promote.check` can make is reached here and must name what it saw. `.github/workflows/release.yml`'s `check` job and
`checks.yml`'s `guards` job run it.

    python3 scripts/promote_selftest.py

What it cannot see: GitHub, GHCR and Sigstore. That the registry, cosign and the REST API answer as the fake does is
the first promotion's to show (inbox #471).
"""
from __future__ import annotations

import base64
import copy
import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import promote  # noqa: E402
import snapshot  # noqa: E402

REG = "ghcr.io/o"
REPO = "o/veyq"
COMMIT = "c" * 40
SET = "sha256:" + "a" * 64
ARCHS = ["amd64", "arm64"]
SHARDS = ["account", "memories"]


def h(n: int) -> str:
    return "sha256:" + f"{n:064x}"


class World:
    """A promotable snapshot 0.12 of COMMIT on main; each test forges one thing."""

    def __init__(self):
        self.releases = [{"id": 7, "draft": True, "tag_name": "snapshot-0.12", "body": "Notes."},
                         {"id": 3, "draft": False, "tag_name": "v0.9", "body": ""}]
        self.ref_major_value = 0
        self.tags = {"snapshot-0.12": COMMIT}
        self.counted = "0.12"
        self.ancestor = True
        self.release_tag_list = ["v0.9", "v0.10"]
        self.registry: dict[str, str] = {}
        self.signed: set[str] = set()
        self.atts: dict[str, str] = {}
        self.produced = SET
        self.tools = {"release": "0.12", "majorEntry": None}
        self.entry = None
        self.matrix = (ARCHS, SHARDS)
        n = 100
        for image in promote.IMAGES:
            per_arch = {}
            kids = []
            for arch in ARCHS:
                arch_kids = [h(n), h(n + 1)]
                n += 2
                per_arch[arch] = json.dumps({"mediaType": snapshot.INDEX_TYPES[0],
                                             "manifests": [{"digest": d} for d in arch_kids], "arch": arch})
                kids += arch_kids
                self.registry[f"{REG}/{image}@{promote.digest_of(per_arch[arch])}"] = per_arch[arch]
            index = json.dumps({"mediaType": snapshot.INDEX_TYPES[0], "manifests": [{"digest": d} for d in kids]})
            self.registry[f"{REG}/{image}:sha-{COMMIT}"] = index
            self.registry[f"{REG}/{image}:0.12-SNAPSHOT"] = index
            d = promote.digest_of(index)
            self.signed.add(f"{REG}/{image}@{d}")
            if image in promote.LANE_IMAGES:
                lines = []
                for arch in ARCHS:
                    lines.append(self.envelope(image, d, arch, promote.digest_of(per_arch[arch])))
                self.atts[f"{REG}/{image}@{d}"] = "\n".join(lines)

    @staticmethod
    def statement(image: str, subject: str, arch: str, per_arch: str, **predicate) -> dict:
        p = {"lane": "L1", "arch": arch, "outcome": "passed", "commit": COMMIT, "run": "r", "shards": SHARDS,
             "images": {image: per_arch}, "inputs": {"assetSet": SET, "predecessor": None, "backups": None}}
        p.update(predicate)
        return {"_type": "https://in-toto.io/Statement/v1", "predicateType": snapshot.PREDICATE_TYPE,
                "subject": [{"name": image, "digest": {"sha256": subject.split(":", 1)[1]}}], "predicate": p}

    def envelope(self, image, subject, arch, per_arch, **predicate) -> str:
        payload = base64.b64encode(json.dumps(self.statement(image, subject, arch, per_arch, **predicate)).encode())
        return json.dumps({"payloadType": "application/vnd.in-toto+json", "payload": payload.decode(),
                           "signatures": [{"sig": "x"}]})

    def digest(self, image: str) -> str:
        return promote.digest_of(self.registry[f"{REG}/{image}:sha-{COMMIT}"])

    # the host interface
    def ref_major(self): return self.ref_major_value
    def tag_commit(self, tag): return self.tags.get(tag)
    def tree_release(self, commit): return self.counted
    def is_ancestor(self, commit): return self.ancestor
    def release_tags(self): return list(self.release_tag_list)

    def inspect(self, ref):
        value = self.registry.get(ref)
        if value == "ERROR":
            self._error()
        return value

    def _error(self):
        try:
            snapshot.read_state(1, "", "502 Bad Gateway")
        except snapshot.Refused as e:
            raise promote.Refused(str(e))

    def verify_signature(self, ref, identity):
        assert identity == promote.snapshot_identity(REPO)
        return ref in self.signed

    def attestations(self, ref, identity):
        return self.atts.get(ref, "")

    def stack_matrix(self, commit): return self.matrix
    def produce_asset_set(self, commit, unscheduled):
        if unscheduled and self.produced == "REFUSE":
            raise promote.Refused("the release files cannot be produced: --unscheduled marks a release that changes "
                                  "the major, and v0.12 does not")
        return self.produced
    def tools_release(self, ref): return dict(self.tools)
    def major_entry(self, commit, version): return self.entry


failures: list[str] = []


def run(world: World, ref: str = "main", unscheduled: bool = False):
    return promote.check(world, releases=world.releases, ref=ref, repository=REPO, registry=REG,
                         unscheduled=unscheduled)


def holds(label: str, world: World, **kw):
    try:
        return run(world, **kw)
    except promote.Refused as e:
        failures.append(f"{label}: refused where it must hold — {e}")


def refuses(label: str, world: World, says: str, drafts: list[int] | None = None, **kw) -> None:
    try:
        plan = run(world, **kw)
    except promote.Refused as e:
        if says not in str(e):
            failures.append(f"{label}: refused, but not by saying `{says}` — it said: {e}")
        if drafts is not None and sorted(e.drafts) != sorted(drafts):
            failures.append(f"{label}: would write the refusal into drafts {e.drafts}, not {drafts}")
        return
    failures.append(f"{label}: PROMOTED ({plan.names}) where it must refuse with `{says}`")


# ---- the world in which a promotion holds ------------------------------------------------------------------------
plan = holds("a promotable snapshot", World())
if plan:
    w = World()
    if plan.names != ["0.12", "0", "latest"] or not plan.latest:
        failures.append(f"from main the names are {plan.names}, latest {plan.latest}")
    if plan.digests != {i: w.digest(i) for i in promote.IMAGES} or plan.commit != COMMIT or plan.release_id != 7:
        failures.append("the plan names other digests, another commit or another draft")
    if plan.chart != "absent" or plan.asset_set != SET:
        failures.append("the plan's chart state or asset set is wrong")
w = World()
w.ref_major_value = 0
w.releases[0]["tag_name"] = "snapshot-0.12"
branch_plan = holds("from release/0.x", w, ref="release/0.x")
if branch_plan and (branch_plan.latest or branch_plan.names != ["0.12", "0"]):
    failures.append(f"from release/0.x the names are {branch_plan.names}, latest {branch_plan.latest} — never `latest`")
w = World()
for image in promote.IMAGES:  # a half-done promotion of THIS snapshot: its names already there
    w.registry[f"{REG}/{image}:0.12"] = w.registry[f"{REG}/{image}:sha-{COMMIT}"]
w.registry[f"{REG}/{promote.CHART}:0.12"] = "{}"
resumed = holds("a promotion that runs again after failing half-way", w)
if resumed and resumed.chart != "present":
    failures.append("a resumed promotion does not keep the chart it pushed")
w = World()
w.atts[f"{REG}/veyq@{w.digest('veyq')}"] = "\n".join(
    json.dumps(json.loads(base64.b64decode(json.loads(line)["payload"])))
    for line in w.atts[f"{REG}/veyq@{w.digest('veyq')}"].splitlines())
holds("attestations printed as bare statements", w)
w = World()
w.releases.append({"id": 9, "draft": False, "tag_name": "v1.0"})  # another major's release does not block 0.12
w.release_tag_list.append("v1.0")
holds("a release of another major", w)
holds("an unscheduled major the producer accepts", World(), unscheduled=True)

# ---- 1. the draft -------------------------------------------------------------------------------------------------
w = World(); w.releases = [r for r in w.releases if not r["draft"]]
refuses("no draft", w, "there is no draft release", drafts=[])
w = World(); w.releases.append({"id": 8, "draft": True, "tag_name": "snapshot-0.11", "body": ""})
refuses("two drafts", w, "2 draft releases exist", drafts=[7, 8])
w = World(); w.releases[0]["tag_name"] = "v0.12"
refuses("a draft on a release tag", w, "not on a `snapshot-<major>.<minor>` tag", drafts=[7])
w = World(); w.releases[0]["tag_name"] = "snapshot-0.12-rc"
refuses("a draft on a tag that only starts like a snapshot's", w, "not on a `snapshot-<major>.<minor>` tag")

# ---- 2. the branch -------------------------------------------------------------------------------------------------
refuses("a feature branch", World(), "a release is promoted from `main` or `release/<major>.x`", ref="feature/x")
refuses("a release branch of another major", World(), "`release/1.x` releases major 1", ref="release/1.x")
w = World(); w.ref_major_value = 1
refuses("main after the bump, promoting the old major", w, "`main` releases major 1")

# ---- 3. the commit -------------------------------------------------------------------------------------------------
w = World(); w.tags = {}
refuses("a tag that names no commit", w, "names no commit")
w = World(); w.counted = "0.13"
refuses("a snapshot tag moved to another commit", w, "whose tree counts 0.13")
w = World(); w.ancestor = False
refuses("a commit not on the run's branch", w, "is not on `main`")

# ---- 4. newer than every release ---------------------------------------------------------------------------------
w = World(); w.release_tag_list.append("v0.12")
refuses("the version is released already", w, "`v0.12` exists already")
w = World(); w.release_tag_list.append("v0.15")
refuses("a newer release exists", w, "`v0.15` exists already")

# ---- 5. the snapshot ---------------------------------------------------------------------------------------------
w = World(); del w.registry[f"{REG}/veyq-admin:sha-{COMMIT}"]
refuses("no sha- tag", w, "has no snapshot of")
w = World(); del w.registry[f"{REG}/veyq-tools:0.12-SNAPSHOT"]
refuses("no -SNAPSHOT tag", w, "has no snapshot of")
w = World(); w.registry[f"{REG}/veyq:0.12-SNAPSHOT"] = w.registry[f"{REG}/veyq-admin:sha-{COMMIT}"]
refuses("the two snapshot names disagree", w, "name two different indexes")
w = World(); w.signed.discard(f"{REG}/veyq-tools@{w.digest('veyq-tools')}")
refuses("an unsigned snapshot", w, "does not verify as a snapshot")
w = World(); w.registry[f"{REG}/veyq:sha-{COMMIT}"] = "ERROR"
refuses("a registry error", w, "other than `not found`")

# ---- 6. release names taken ----------------------------------------------------------------------------------------
w = World(); w.registry[f"{REG}/veyq:0.12"] = w.registry[f"{REG}/veyq-admin:sha-{COMMIT}"]
refuses("an image's release name names another index", w, "already names")
w = World(); w.registry[f"{REG}/{promote.CHART}:0.12"] = "{}"
refuses("a chart released without its images", w, "the chart is another publish's")

# ---- 7. attestations ---------------------------------------------------------------------------------------------
w = World(); w.atts[f"{REG}/veyq@{w.digest('veyq')}"] = ""
refuses("no attestation", w, "no L1 attestation for amd64")
w = World(); w.matrix = (ARCHS + ["riscv64"], SHARDS)
refuses("an architecture the lane runs but nothing attests", w, "no L1 attestation for riscv64")
w = World(); w.matrix = (ARCHS, SHARDS + ["depot"])
refuses("a shard the attestation misses", w, "its shards are")
w = World(); w.matrix = ([], SHARDS)
refuses("a stack matrix with no architecture", w, "names no architecture")


def forge(world: World, image: str, **predicate) -> None:
    d = world.digest(image)
    lines = []
    for arch in ARCHS:
        per_arch = promote.digest_of(next(v for k, v in world.registry.items()
                                          if k.startswith(f"{REG}/{image}@") and f'"arch": "{arch}"' in v))
        lines.append(world.envelope(image, d, arch, per_arch, **predicate))
    world.atts[f"{REG}/{image}@{d}"] = "\n".join(lines)


w = World(); forge(w, "veyq", outcome="failed")
refuses("a failed lane", w, "its outcome is `failed`")
w = World(); forge(w, "veyq-admin", commit="d" * 40)
refuses("an attestation of another commit", w, "it is of commit")
w = World(); forge(w, "veyq", lane="L9")
refuses("an attestation of another lane only", w, "no L1 attestation for amd64")
w = World(); forge(w, "veyq", images={"veyq-admin": h(1)})
refuses("an attestation naming no digest of the image", w, "it names no veyq digest")
w = World(); forge(w, "veyq", inputs={"assetSet": SET, "predecessor": h(5), "backups": None})
refuses("a lane input this promotion cannot compare", w, "the rerun of a lane whose inputs moved is not built")
w = World(); forge(w, "veyq", inputs={"predecessor": None})
refuses("an attestation with no asset set", w, "it records no asset set")
w = World()
d = w.digest("veyq")
w.atts[f"{REG}/veyq@{d}"] = w.atts[f"{REG}/veyq-admin@{w.digest('veyq-admin')}"]
refuses("an attestation about another index", w, "its subject is another digest")
w = World()
d = w.digest("veyq")
w.atts[f"{REG}/veyq@{d}"] = "\n".join(
    w.envelope("veyq", d, arch, h(999)) for arch in ARCHS)  # names a build the index does not hold
w.registry[f"{REG}/veyq@{h(999)}"] = json.dumps({"manifests": [{"digest": h(998)}]})
refuses("an index holding builds no lane attested", w, "that no attested build stands for")
w = World()
forge(w, "veyq-admin", inputs={"assetSet": "sha256:" + "b" * 64, "predecessor": None, "backups": None})
refuses("two asset sets across the lanes", w, "2 different asset sets")
w = World(); w.atts[f"{REG}/veyq@{w.digest('veyq')}"] = "not json\n{\"payload\": \"%%%\"}"
refuses("an attestation output of an unknown shape", w, "no L1 attestation for amd64")

# ---- 8. inputs -------------------------------------------------------------------------------------------------------
w = World(); w.produced = "sha256:" + "f" * 64
refuses("release files that differ from the tested ones", w, "the published files would not be the tested ones")
w = World(); w.produced = "REFUSE"
refuses("an unscheduled major the producer refuses", w, "--unscheduled marks a release", unscheduled=True)
w = World(); w.tools = {"release": "0.12-SNAPSHOT", "majorEntry": None}
refuses("a snapshot from before a release could promote one", w, "stamped `0.12-SNAPSHOT`")
w = World(); w.entry = "0.9"
refuses("a major entry that moved since the snapshot", w, "a release published since moved it")

# ---- the pure parts --------------------------------------------------------------------------------------------------
identity = promote.snapshot_identity(REPO)
import re  # noqa: E402
for subject, want in (
    (f"https://github.com/{REPO}/.github/workflows/publish.yml@refs/heads/main", True),
    (f"https://github.com/{REPO}/.github/workflows/publish.yml@refs/heads/release/0.x", True),
    (f"https://github.com/{REPO}/.github/workflows/release.yml@refs/heads/main", False),
    # The workflow that signed snapshots before the split, and the pipeline publish.yml calls (cr-workflows-split-
    # pipeline-callers): neither is the file that holds `snapshot`, so neither is a snapshot's identity.
    (f"https://github.com/{REPO}/.github/workflows/ci.yml@refs/heads/main", False),
    (f"https://github.com/{REPO}/.github/workflows/pipeline.yml@refs/heads/main", False),
    (f"https://github.com/{REPO}/.github/workflows/checks.yml@refs/heads/main", False),
    (f"https://github.com/{REPO}/.github/workflows/publish.yml@refs/pull/1/merge", False),
    (f"https://github.com/{REPO}/.github/workflows/publish.yml@refs/tags/snapshot-0.12", False),
    (f"https://github.com/{REPO}-fork/.github/workflows/publish.yml@refs/heads/main", False),
    (f"https://github.com/x{REPO}/.github/workflows/publish.yml@refs/heads/main", False),
):
    if bool(re.search(identity, subject)) != want:
        failures.append(f"the snapshot identity {'rejects' if want else 'accepts'} {subject}")
try:
    promote.snapshot_identity("o/veyq/../x")
    failures.append("a repository with a path in it builds an identity")
except promote.Refused:
    pass
body = promote.note("Owner's notes.\n", "the tag is wrong", "https://run")
if not body.startswith("Owner's notes.") or promote.NOTE_MARK not in body or "the tag is wrong" not in body:
    failures.append(f"the refusal note loses the owner's text or the reason: {body!r}")
if promote.note(body, "again", "u").count(promote.NOTE_MARK) != 2:
    failures.append("a second refusal does not add a second note")

# ---- the produced release files against the plan ------------------------------------------------------------------
import release_assets  # noqa: E402
with tempfile.TemporaryDirectory(prefix="promote_selftest_") as tmp:
    digests = {i: h(n) for n, i in enumerate(promote.IMAGES, 1)}
    refs = {i: f"{REG}/{i}:0.12@{digests[i]}" for i in promote.IMAGES}
    dest = release_assets.produce(pathlib.Path(tmp), refs["veyq"], refs["veyq-admin"], False, refs["veyq-tools"],
                                  release="0.12")
    good = {"version": "0.12", "digests": digests, "asset_set": release_assets.asset_set_digest(dest),
            "major_entry": json.loads((dest / "manifest.json").read_text())["majorEntry"], "unscheduled": False}
    for label, change, says in (
        ("the produced files", {}, None),
        ("another asset set", {"asset_set": SET}, "hash to"),
        ("another digest", {"digests": {**digests, "veyq-admin": h(9)}}, "names admin as"),
        ("another major entry", {"major_entry": "0.1"}, "majorEntry"),
        ("another unscheduled", {"unscheduled": True}, "another `unscheduled`"),
    ):
        try:
            promote.check_assets(dest, {**copy.deepcopy(good), **change}, REG)
            if says:
                failures.append(f"assets: {label} passes")
        except promote.Refused as e:
            if not says:
                failures.append(f"assets: {label} refused: {e}")
            elif says not in str(e):
                failures.append(f"assets: {label} refused without `{says}`: {e}")

if failures:
    print("PROMOTE SELFTEST FAILED:")
    for message in failures:
        print("  -", message)
    sys.exit(1)
print("promote selftest OK: a promotable snapshot holds from main (latest) and from its release branch (not latest), "
      "a half-done promotion resumes, and every refusal — draft, branch, commit, newer, snapshot, names, "
      "attestations, inputs, stamp — is reached and named")
