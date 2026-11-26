#!/usr/bin/env python3
"""Self-test of a snapshot's pure decisions (ADR 0091 decision 5), offline and Docker-free: the asset-set hash
(`release_assets.asset_set_digest`) and `scripts/snapshot.py`'s lane records, predicates, write-once verdicts, and
which commit and Checks run a snapshot is made from (a docs-only HEAD's walk to its tested ancestor included, on a
throwaway git history) and that its staged bytes are that run's (on recorded API shapes).
`checks.yml`'s `guards` job runs it.

    python3 scripts/snapshot_selftest.py

What it cannot see: the registry. Whether Buildx words a missing tag the way `snapshot.NOT_FOUND` expects, and whether
`cosign attest` stores the predicate where the promotion finds it, are a run's to show (inbox #471).
"""
from __future__ import annotations

import contextlib
import hashlib
import io
import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_assets  # noqa: E402
import snapshot  # noqa: E402

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


def refused(fn, *args) -> bool:
    try:
        fn(*args)
    except (snapshot.Refused, release_assets.ReleaseError):
        return True
    return False


SHA = "a" * 40
D1, D2, D3, D4 = ("sha256:" + c * 64 for c in "1234")
SET = "sha256:" + "e" * 64

# ---- the asset-set hash -----------------------------------------------------------------------------------------
with tempfile.TemporaryDirectory(prefix="snapshot_selftest_") as tmp:
    base = pathlib.Path(tmp)
    one = release_assets.produce(base / "one", "veyq:candidate-amd64", "veyq-admin:candidate-amd64", False, release="0.3")
    two = release_assets.produce(base / "two", f"ghcr.io/o/veyq:0.3@{D1}", f"ghcr.io/o/veyq-admin:0.3@{D2}", True,
                                 f"ghcr.io/o/veyq-tools:0.3@{D3}", release="0.3")
    first = release_assets.asset_set_digest(one)
    # `veyqctl` as a release carries it: the tree's source, its one RELEASE line naming the release (ADR 0096 decision 9).
    tree_veyqctl = release_assets.TOOLS["veyqctl"].read_text()
    carried = (one / "veyqctl").read_text()
    check(carried == tree_veyqctl.replace('RELEASE: str | None = None', 'RELEASE: str | None = "0.3"', 1)
          and carried != tree_veyqctl, "the release's veyqctl is not the tree's source stamped with its release")
    check(refused(release_assets.stamp_veyqctl, carried, "0.4"), "a veyqctl stamped already is stamped again")
    check(first.startswith("sha256:") and len(first) == 71, f"the hash is not `sha256:<64 hex>`: {first}")
    check(release_assets.asset_set_digest(two) == first,
          "another production of the same tree (release image refs, a tools image, --unscheduled) hashes differently")
    manifest = json.loads((one / release_assets.MANIFEST).read_text())
    manifest["date"] = "1999-01-01"
    (one / release_assets.MANIFEST).write_text(json.dumps(manifest))
    check(release_assets.asset_set_digest(one) == first, "the manifest's date changes the asset-set hash")
    # One recipe byte changed, and listed: another set, another hash.
    name = sorted(manifest["assets"])[0]
    changed = (one / name).read_bytes() + b"\n"
    (one / name).write_bytes(changed)
    check(refused(release_assets.asset_set_digest, one), "a file that is not the one the manifest lists is hashed")
    manifest["assets"][name] = hashlib.sha256(changed).hexdigest()
    (one / release_assets.MANIFEST).write_text(json.dumps(manifest))
    check(release_assets.asset_set_digest(one) != first, "a changed recipe file leaves the asset-set hash unchanged")
    (one / release_assets.MANIFEST).write_text(json.dumps({"assets": {}}))
    check(refused(release_assets.asset_set_digest, one), "an empty asset list is hashed")

# ---- lane records and predicates ---------------------------------------------------------------------------------
check(refused(snapshot.lane_result, "L9", "amd64", "x", SHA, SET, {"veyq": D1}), "an unknown lane is recorded")
check(refused(snapshot.lane_result, "L1", "amd64", "x", "abc", SET, {"veyq": D1}), "a short commit is recorded")
check(refused(snapshot.lane_result, "L1", "amd64", "x", SHA, "sha256:1", {"veyq": D1}), "a bad asset-set hash is recorded")
check(refused(snapshot.lane_result, "L1", "amd64", "x", SHA, SET, {"veyq": "latest"}), "a tag is recorded as a digest")
check(refused(snapshot.lane_result, "L1", "amd64", "x", SHA, SET, {}), "a leg with no image is recorded")


def legs(**override) -> list[dict]:
    out = []
    for arch, images in (("amd64", {"veyq": D1, "veyq-admin": D2}), ("arm64", {"veyq": D3, "veyq-admin": D4})):
        for shard in ("account", "memories"):
            out.append(snapshot.lane_result("L1", arch, shard, SHA, SET, images))
    for record in out:
        record.update(override)
    return out


ARCHS, SHARDS = ["amd64", "arm64"], ["account", "memories"]
got = snapshot.predicates(legs(), "L1", ARCHS, SHARDS, SHA, "https://run")
check(sorted(got) == ARCHS, f"predicates for {sorted(got)}, not {ARCHS}")
check(got["arm64"]["images"] == {"veyq": D3, "veyq-admin": D4}, "the arm64 predicate names other digests")
check(got["amd64"]["inputs"] == {"assetSet": SET, "predecessor": None, "backups": None},
      f"the inputs are {got['amd64']['inputs']}")
check(got["amd64"]["shards"] == SHARDS and got["amd64"]["outcome"] == "passed", "shards or outcome wrong")
check(refused(snapshot.predicates, legs()[1:], "L1", ARCHS, SHARDS, SHA, "r"), "a missing leg is attested")
check(refused(snapshot.predicates, legs() + legs()[:1], "L1", ARCHS, SHARDS, SHA, "r"), "a duplicate leg is attested")
check(refused(snapshot.predicates, legs(), "L1", ARCHS, SHARDS[:1], SHA, "r"), "an unexpected shard is attested")
check(refused(snapshot.predicates, legs(), "L1", ["amd64", "arm64", "riscv64"], SHARDS, SHA, "r"),
      "an architecture with no leg is attested")
check(refused(snapshot.predicates, legs(outcome="failed"), "L1", ARCHS, SHARDS, SHA, "r"), "a failed leg is attested")
check(refused(snapshot.predicates, legs(commit="b" * 40), "L1", ARCHS, SHARDS, SHA, "r"), "another commit is attested")
check(refused(snapshot.predicates, legs(lane="L2"), "L1", ARCHS, SHARDS, SHA, "r"), "another lane is attested")
mixed = legs()
mixed[0]["assetSet"] = "sha256:" + "f" * 64
check(refused(snapshot.predicates, mixed, "L1", ARCHS, SHARDS, SHA, "r"), "two asset sets are attested as one")
mixed = legs()
mixed[0]["images"] = {"veyq": D3, "veyq-admin": D2}
check(refused(snapshot.predicates, mixed, "L1", ARCHS, SHARDS, SHA, "r"), "two app digests in one architecture attested")
check(refused(snapshot.predicates, [], "L1", [], SHARDS, SHA, "r"), "an empty expectation attests nothing and passes")

# Lane L2: every record carries its predecessor — the release it upgraded from, or none — and the predicate names it
# per leg; no other lane records one.
NEWEST = {"tag": "v1.318", "images": {"veyq": D3, "veyq-admin": D4}}
check(refused(snapshot.lane_result, "L2", "amd64", "newest", SHA, SET, {"veyq": D1}), "an L2 leg records no predecessor")
check(refused(snapshot.lane_result, "L1", "amd64", "x", SHA, SET, {"veyq": D1}, NEWEST, True),
      "an L1 leg records a predecessor")
check(refused(snapshot.lane_result, "L2", "amd64", "newest", SHA, SET, {"veyq": D1}, {"tag": "1.318", "images": {}}, True),
      "a predecessor that names no release tag is recorded")
check(refused(snapshot.lane_result, "L2", "amd64", "newest", SHA, SET, {"veyq": D1},
              {"tag": "v1.318", "images": {"veyq": D3}}, True), "a predecessor without its admin digest is recorded")
check(snapshot.lane_result("L2", "amd64", "major-entry", SHA, SET, {"veyq": D1}, None, True)["predecessor"] is None,
      "a leg with no release to start from does not record none")


def upgrade_legs_of(newest=NEWEST, entry=None, **override) -> list[dict]:
    out = []
    for arch, images in (("amd64", {"veyq": D1, "veyq-admin": D2}), ("arm64", {"veyq": D3, "veyq-admin": D4})):
        for leg, predecessor in (("newest", newest), ("major-entry", entry)):
            out.append(snapshot.lane_result("L2", arch, leg, SHA, SET, images, predecessor, True))
    for record in out:
        record.update(override)
    return out


got = snapshot.predicates(upgrade_legs_of(), "L2", ARCHS, ["major-entry", "newest"], SHA, "https://run")
check(got["arm64"]["inputs"]["predecessor"] == {"newest": NEWEST, "major-entry": None},
      f"the L2 predicate names {got['arm64']['inputs']['predecessor']} as its predecessors")
moved = upgrade_legs_of()
moved[2]["predecessor"] = {"tag": "v1.312", "images": {"veyq": D3, "veyq-admin": D4}}
check(refused(snapshot.predicates, moved, "L2", ARCHS, ["major-entry", "newest"], SHA, "r"),
      "two architectures that started one leg from different releases are attested as one")
lost = upgrade_legs_of()
del lost[0]["predecessor"]
check(refused(snapshot.predicates, lost, "L2", ARCHS, ["major-entry", "newest"], SHA, "r"),
      "an L2 record without its predecessor is attested")
smuggled = legs()
smuggled[0]["predecessor"] = NEWEST
check(refused(snapshot.predicates, smuggled, "L1", ARCHS, SHARDS, SHA, "r"), "an L1 record's predecessor is attested")

# ---- write-once ---------------------------------------------------------------------------------------------------
index = json.dumps({"mediaType": snapshot.INDEX_TYPES[0], "manifests": [{"digest": D1}, {"digest": D2}]})
check(snapshot.children(index) == {D1, D2}, "an index's children are not its entries")
plain = json.dumps({"mediaType": "application/vnd.oci.image.manifest.v1+json", "layers": []})
check(snapshot.children(plain) == {"sha256:" + hashlib.sha256(plain.encode()).hexdigest()},
      "a single manifest does not stand for itself")
check(snapshot.read_state(0, index, "") == index, "a read that succeeded is not its manifest")
check(snapshot.read_state(1, "", "ERROR: ghcr.io/o/veyq:0.3-SNAPSHOT: not found") is None, "`not found` is not absent")
check(snapshot.read_state(1, "", "MANIFEST_UNKNOWN: manifest unknown") is None, "MANIFEST_UNKNOWN is not absent")
check(refused(snapshot.read_state, 1, "", "error: 502 Bad Gateway"), "a transient error reads as absent")
check(refused(snapshot.read_state, 1, "", "unauthorized: authentication required"), "a refused login reads as absent")
check(snapshot.decide("i", {"0.3-SNAPSHOT": None, f"sha-{SHA}": None}, {D1, D2}) is False, "free names are not free")
check(snapshot.decide("i", {"0.3-SNAPSHOT": index, f"sha-{SHA}": None}, {D1, D2}) is True,
      "this build's own half-published names are refused")
other = json.dumps({"manifests": [{"digest": D3}, {"digest": D4}]})
check(refused(snapshot.decide, "i", {"0.3-SNAPSHOT": other, f"sha-{SHA}": None}, {D1, D2}),
      "another build's snapshot name is replaced")
check(refused(snapshot.decide, "i", {"0.3-SNAPSHOT": index, f"sha-{SHA}": other}, {D1, D2}),
      "a sha tag of another build is replaced")

# ---- the Checks run a snapshot is made from --------------------------------------------------------------------
ARCHS, DOMAINS = ["amd64", "arm64"], ["account", "memories"]
LEGS = ["major-entry", "newest"]


def run_of(**override) -> dict:
    run = {"id": 7, "run_number": 70, "run_attempt": 2, "head_sha": SHA, "head_branch": "main", "event": "push",
           "path": ".github/workflows/checks.yml", "status": "completed", "conclusion": "success",
           "html_url": "https://github.com/o/veyq/actions/runs/7", "created_at": "2026-11-07T10:00:00Z"}
    run.update(override)
    return run


def jobs_of(drop: str = "", **conclusions) -> list[dict]:
    names = ([snapshot.GATE_JOB_NAME] + [snapshot.LANE_JOB_NAME.format(arch=a, domain=d) for a in ARCHS for d in DOMAINS]
             + [snapshot.UPGRADE_JOB_NAME.format(arch=a, leg=leg) for a in ARCHS for leg in LEGS])
    return [{"name": n, "run_attempt": 2, "conclusion": conclusions.get(n, "success")} for n in names if n != drop]


good = snapshot.checks_verdict(run_of(), jobs_of(), ARCHS, DOMAINS, LEGS)
check(good == {"run_id": "7", "run_url": "https://github.com/o/veyq/actions/runs/7", "attempt": "2"},
      f"a wholly green Checks run is not the one: {good}")
newer = run_of(id=8, run_number=71, conclusion="failure")
check(snapshot.pick_run([run_of(), newer], SHA, "main")["id"] == 8, "an older green run hides the newest, red one")
check(refused(snapshot.pick_run, [run_of(head_sha="b" * 40)], SHA, "main"), "another commit's run is taken")
check(refused(snapshot.pick_run, [run_of(event="pull_request")], SHA, "main"), "a pull request's run is taken")
check(refused(snapshot.pick_run, [run_of(head_branch="release/0.x")], SHA, "main"), "another branch's run is taken")
check(refused(snapshot.pick_run, [run_of(path=".github/workflows/publish.yml")], SHA, "main"),
      "another workflow's run is taken")
check(refused(snapshot.pick_run, [], SHA, "main"), "no Checks run at all is not refused")


class FakeTime:
    def __init__(self):
        self.now, self.slept = 0.0, []

    def sleep(self, seconds: float) -> None:
        self.slept.append(seconds)
        self.now += seconds

    def clock(self) -> float:
        return self.now


def awaited(statuses: list[str], wait: int, start: str = "in_progress"):
    t, reads = FakeTime(), list(statuses)

    def fetch(run_id):
        return run_of(id=run_id, status=reads.pop(0) if reads else "in_progress", conclusion=None)
    with contextlib.redirect_stdout(io.StringIO()):
        got = snapshot.await_completion(run_of(id=5, status=start, conclusion=None), fetch, wait, sleep=t.sleep,
                                        clock=t.clock)
    return got, t, len(statuses) - len(reads)


got, t, fetched = awaited(["in_progress", "completed"], 0)
check(got["status"] == "in_progress" and fetched == 0 and not t.slept, "a dispatch (no wait) waits or re-reads the run")
got, t, fetched = awaited(["queued", "in_progress", "completed"], 7200, start="pending")
check(got["status"] == "completed" and fetched == 3 and t.now == 180,
      f"a running Checks run is not re-read each minute until it completes: {got['status']}, {fetched} reads, {t.now}s")
got, t, fetched = awaited([], 150)
check(got["status"] != "completed" and t.now == 150 and refused(snapshot.checks_verdict, got, jobs_of(), ARCHS, DOMAINS, LEGS),
      f"the wait runs past its bound, or a run still going after it is published from: {t.now}s, {t.slept}")
got, t, fetched = awaited(["completed"], 7200, start="completed")
check(fetched == 0 and not t.slept, "a completed run is waited for")
check(refused(snapshot.checks_verdict, run_of(status="in_progress", conclusion=None), jobs_of(), ARCHS, DOMAINS, LEGS),
      "a running Checks run is published from")
check(refused(snapshot.checks_verdict, run_of(conclusion="cancelled"), jobs_of(), ARCHS, DOMAINS, LEGS),
      "a Checks run cancelled by hand is published from")
check(refused(snapshot.checks_verdict, run_of(conclusion="failure"), jobs_of(), ARCHS, DOMAINS, LEGS),
      "a red Checks run is published from")
check(refused(snapshot.checks_verdict, run_of(), jobs_of(**{snapshot.GATE_JOB_NAME: "skipped"}), ARCHS, DOMAINS, LEGS),
      "a run whose gate did not pass is published from")
lane = snapshot.LANE_JOB_NAME.format(arch="arm64", domain="memories")
check(refused(snapshot.checks_verdict, run_of(), jobs_of(drop=lane), ARCHS, DOMAINS, LEGS),
      "a run without an arm64 lane leg is published from")
check(refused(snapshot.checks_verdict, run_of(), jobs_of(**{lane: "cancelled"}), ARCHS, DOMAINS, LEGS),
      "a run whose lane leg was cancelled is published from")
upgrade = snapshot.UPGRADE_JOB_NAME.format(arch="arm64", leg="major-entry")
check(refused(snapshot.checks_verdict, run_of(), jobs_of(drop=upgrade), ARCHS, DOMAINS, LEGS),
      "a run without an arm64 lane L2 leg is published from")
check(refused(snapshot.checks_verdict, run_of(), jobs_of(**{upgrade: "failure"}), ARCHS, DOMAINS, LEGS),
      "a run whose lane L2 leg failed is published from")
check(refused(snapshot.checks_verdict, run_of(), jobs_of(), ARCHS, DOMAINS, []),
      "a commit whose checks.yml names no lane L2 leg is published from")
stale = [dict(j, run_attempt=1) for j in jobs_of()]
check(refused(snapshot.checks_verdict, run_of(), stale, ARCHS, DOMAINS, LEGS), "an older attempt's jobs are read")

# ---- the publishable commit: a docs-only HEAD publishes its newest tested ancestor -----------------------------------
PATTERNS = ["**/*.md", "docs/**", "site/**"]
ignored = snapshot.ignored_by(PATTERNS)
for path, want in (("README.md", True), ("src/web/README.md", True), ("a/b/c/d.md", True), ("docs/x/y.png", True),
                   ("site/src/pages/index.astro", True), ("README.mdx", False), ("src/docs/a.kt", False),
                   ("sites/a.txt", False), ("docs", False), ("VERSION", False), (".github/workflows/checks.yml", False),
                   ("scripts/snapshot.py", False)):
    check(ignored(path) is want, f"`{path}` is {'not ' if want else ''}ignored by {PATTERNS}, as GitHub reads them")
negated = snapshot.ignored_by(["docs/**", "!docs/api/**"])
check(negated("docs/a.md") and not negated("docs/api/openapi.yaml"), "a later `!` pattern does not re-include its path")
check(refused(snapshot.ignored_by, ["docs/?.md"]), "a glob this reader cannot match like GitHub is guessed at")
check(refused(snapshot.ignored_by, ["src/{a,b}/**"]), "a brace glob is guessed at")
real = snapshot.push_paths_ignore(pathlib.Path(__file__).resolve().parent.parent)
check(bool(real) and all(snapshot.ignored_by([p]) for p in real), f"checks.yml's push paths-ignore is not read: {real}")
with tempfile.TemporaryDirectory(prefix="snapshot_filter_") as tmp:
    fake = pathlib.Path(tmp)
    (fake / ".github/workflows").mkdir(parents=True)
    workflow = fake / snapshot.CHECKS_WORKFLOW
    workflow.write_text('on:\n  push:\n    paths-ignore:\n      - "x/**"\n  pull_request:\n    paths-ignore:\n      - "y/**"\n')
    check(snapshot.push_paths_ignore(fake) == ["x/**"], "the push filter is not the one read from checks.yml's file")
    workflow.write_text('on:\n  push:\n    paths:\n      - "src/**"\n')
    check(refused(snapshot.push_paths_ignore, fake), "a `paths:` push filter is read as if it were `paths-ignore`")
    workflow.write_text('on:\n  pull_request: {}\n')
    check(refused(snapshot.push_paths_ignore, fake), "a checks.yml with no push trigger explains a missing run")

H, M, C, OLD = ("1" * 40), ("2" * 40), ("3" * 40), ("4" * 40)


def world(runs: dict[str, list[dict]], files: dict[str, list[str] | None], tags: dict[str, str] | None = None):
    return (lambda c: (tags or {}).get(c, ""), lambda c: runs.get(c, []), lambda c: files.get(c, ["src/x.kt"]))


def walk(chain: list[str], runs, files, tags=None, bound: int = snapshot.WALK_BOUND, head: str = H) -> dict:
    """The walk's verdict, or `{"refused": <message>}` — a refusal where a commit was expected is a failed check here,
    never a crash of the selftest."""
    tags_of, runs_of, files_of = world(runs, files, tags)
    try:
        return snapshot.publishable(head, "main", chain, tags_of, runs_of, files_of, ignored, bound)
    except snapshot.Refused as e:
        return {"refused": str(e) or "refused"}


def walk_refused(*args, **kwargs) -> str:
    """The refusal's message, or '' when the walk was not refused."""
    return walk(*args, **kwargs).get("refused", "")


got = walk([H, M, C], {H: [run_of(head_sha=H)], C: [run_of(head_sha=C)]}, {})
check(got.get("commit") == H and got.get("skipped") == [] and (got.get("run") or {}).get("head_sha") == H,
      f"a HEAD with its own Checks run is not the one published: {got}")
got = walk([H, M, C, OLD], {C: [run_of(head_sha=C, id=9)], OLD: [run_of(head_sha=OLD)]},
           {H: ["site/src/pages/donate.astro"], M: ["docs/decisions/0091.md", "README.md"]})
check(got.get("commit") == C and got.get("skipped") == [H, M] and (got.get("run") or {}).get("id") == 9,
      f"a docs-only HEAD does not publish its newest tested ancestor: {got}")
check(walk_refused([H, M, C], {C: [run_of(head_sha=C)]}, {H: ["docs/a.md"], M: ["docs/b.md", "scripts/x.py"]}),
      "a code commit with no Checks run between HEAD and the tested ancestor is skipped")
check(walk_refused([H, C], {C: [run_of(head_sha=C)]}, {H: ["src/web/src/main.ts"]}),
      "a code HEAD with no Checks run publishes its ancestor")
check(walk_refused([H, M, C], {C: [run_of(head_sha=C, head_branch="release/0.x")]}, {H: ["docs/a.md"], M: ["docs/b.md"],
      C: ["docs/c.md"]}), "another branch's run is taken for the ancestor")
chain = [H] + [f"{n:040x}" for n in range(5, 5 + 60)]
docs = {c: ["docs/a.md"] for c in chain}
check(walk_refused(chain, {chain[-1]: [run_of(head_sha=chain[-1])]}, docs, bound=50),
      "a walk past its bound finds a tested commit")
check(walk([H, M, C], {C: [run_of(head_sha=C)]}, {H: [], M: ["docs/a.md"]}, bound=3).get("commit") == C,
      "an empty commit (it changes no path) stops the walk")
check(walk_refused([H, M, C], {C: [run_of(head_sha=C)]}, {H: ["docs/a.md"], M: ["docs/a.md"]}, bound=2),
      "the walk reads past its bound")
check("the first commit of the history" in walk_refused([H, M], {}, {H: ["docs/a.md"], M: None}),
      "the walk passes the first commit of the history, or does not say it stopped there")
got = walk([H, M, C], {C: [run_of(head_sha=C)]}, {H: ["docs/a.md"], M: ["docs/b.md"]}, tags={C: "snapshot-0.12"})
check(got.get("published") == "snapshot-0.12" and got.get("commit") == C and got.get("run") is None,
      f"a docs-only HEAD over a published snapshot publishes again: {got}")
check(walk([H, C], {H: [run_of(head_sha=H)]}, {}, tags={H: "snapshot-0.13"}).get("published") == "snapshot-0.13",
      "a HEAD that carries its snapshot publishes again")
check(walk_refused([M, C], {C: [run_of(head_sha=C)]}, {M: ["docs/a.md"]}), "a walk that does not start at HEAD is taken")
for tags, want in ((["snapshot-1.247"], "snapshot-1.247"), (["v1.247"], "v1.247"), (["v1.247", "x"], "v1.247"),
                   (["v1.247-rc"], ""), (["v1"], ""), (["release-1.2"], ""), ([], "")):
    check(snapshot.published_by(tags) == want, f"the tags {tags} make a commit published as {snapshot.published_by(tags)!r}, "
                                               f"not {want!r}")
for published, want in (("snapshot-1.262", "1.262"), ("v1.262", ""), ("snapshot-1.262 v1.262", ""),
                        ("v1.262 snapshot-1.262", ""), ("snapshot-1.261 snapshot-1.262", ""), ("", ""),
                        ("snapshot-1.262-rc", "")):
    check(snapshot.unreleased_snapshot(published) == want,
          f"the published tags {published!r} name {snapshot.unreleased_snapshot(published)!r} for a draft, not {want!r}")


# The wiring, on a throwaway history: `checks_run` walks git, reads checks.yml's filter, counts C's version, names C.
def git(cwd: pathlib.Path, *args: str) -> str:
    import subprocess
    return subprocess.run(["git", "-c", "user.name=selftest", "-c", "user.email=selftest@invalid", "-c",
                           "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


with tempfile.TemporaryDirectory(prefix="snapshot_walk_") as tmp:
    repo = pathlib.Path(tmp)
    root_repo = pathlib.Path(__file__).resolve().parent.parent
    git(repo, "init", "-q", "-b", "main")
    (repo / ".github/workflows").mkdir(parents=True)
    (repo / snapshot.CHECKS_WORKFLOW).write_text((root_repo / snapshot.CHECKS_WORKFLOW).read_text())
    (repo / "VERSION").write_text("0\n")
    (repo / "docs").mkdir()
    (repo / "docs/a.md").write_text("a\n")
    git(repo, "add", "VERSION", "docs/a.md", snapshot.CHECKS_WORKFLOW)
    git(repo, "commit", "-q", "-m", "root")
    check(snapshot.changed_files(repo, git(repo, "rev-parse", "HEAD")) is None, "a root commit has a first parent")
    (repo / "src.kt").write_text("x\n")
    git(repo, "add", "src.kt")
    git(repo, "commit", "-q", "-m", "code, tested")
    tested = git(repo, "rev-parse", "HEAD")
    (repo / "README.md").write_text("r\n")
    git(repo, "add", "README.md")
    git(repo, "commit", "-q", "-m", "docs")
    (repo / "docs/a.md").write_text("b\n")
    git(repo, "add", "docs/a.md")
    git(repo, "commit", "-q", "-m", "docs again")
    head = git(repo, "rev-parse", "HEAD")
    check(snapshot.changed_files(repo, head) == ["docs/a.md"], f"{snapshot.changed_files(repo, head)}")
    green = {"workflow_runs": [run_of(head_sha=tested, id=11)]}
    # The stack matrix the commit's own checks.yml names: the fake run must carry a leg for each.
    archs, domains = snapshot.stack_matrix(repo)
    jobs = {"jobs": [{"name": n, "run_attempt": 2, "conclusion": "success"} for n in
                     [snapshot.GATE_JOB_NAME] + [snapshot.LANE_JOB_NAME.format(arch=a, domain=d)
                                                 for a in archs for d in domains]
                     + [snapshot.UPGRADE_JOB_NAME.format(arch=a, leg=leg) for a in archs
                        for leg in snapshot.upgrade_legs(repo)]]}
    check(snapshot.upgrade_legs(repo) == LEGS, f"checks.yml's lane L2 legs are {snapshot.upgrade_legs(repo)}, not {LEGS}")
    real_gh = snapshot._gh_json
    snapshot._gh_json = lambda url: (jobs if "/jobs?" in url else green if f"head_sha={tested}" in url
                                     else {"workflow_runs": []})
    def quiet(commit: str) -> dict:
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                return snapshot.checks_run("o/veyq", commit, "main", repo)
        except snapshot.Refused as e:
            return {"refused": str(e)}

    try:
        found = quiet(head)
        check(found == {"kind": "snapshot", "commit": tested, "run_id": "11", "attempt": "2", "version": "0.1",
                        "run_url": "https://github.com/o/veyq/actions/runs/7", "draft_version": "0.1"},
              f"a docs-only HEAD's publish does not name its tested ancestor and that commit's version: {found}")
        pending = {"workflow_runs": [run_of(head_sha=tested, id=11, status="queued", conclusion=None)]}
        reads = []

        def later(url):
            if url.endswith("/actions/runs/11"):
                reads.append(url)
                return run_of(head_sha=tested, id=11)
            return jobs if "/jobs?" in url else pending if f"head_sha={tested}" in url else {"workflow_runs": []}
        snapshot._gh_json = later
        fake = FakeTime()
        real_time = snapshot.time.sleep, snapshot.time.monotonic
        snapshot.time.sleep, snapshot.time.monotonic = fake.sleep, fake.clock
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                waited = snapshot.checks_run("o/veyq", head, "main", repo, wait_seconds=600)
        finally:
            snapshot.time.sleep, snapshot.time.monotonic = real_time
        check(waited.get("kind") == "snapshot" and reads,
              f"the schedule's checks-run does not wait for a queued Checks run and publish it once green: {waited}")
        check("refused" in quiet(head), "a dispatch's checks-run (no wait) publishes from a queued Checks run")
        snapshot._gh_json = lambda url: (jobs if "/jobs?" in url else green if f"head_sha={tested}" in url
                                         else {"workflow_runs": []})
        git(repo, "tag", "snapshot-0.1", tested)
        found = quiet(head)
        check(found.get("kind") == "none" and found.get("commit") == tested,
              f"a docs-only HEAD over a tested ancestor that has its snapshot publishes again: {found}")
        check(found.get("draft_version") == "0.1",
              f"a run over an unreleased snapshot does not name it for its draft to be refreshed: {found}")
        git(repo, "tag", "-d", "snapshot-0.1")
        git(repo, "tag", "v0.1", tested)
        found = quiet(head)
        check(found.get("kind") == "none" and found.get("commit") == tested,
              f"a released commit whose snapshot tag the release deleted is published as a snapshot again: {found}")
        check(found.get("draft_version") == "",
              f"a released commit gets its draft written again: {found}")
        git(repo, "tag", "-d", "v0.1")
        git(repo, "mv", "docs/a.md", "a.kt")
        git(repo, "commit", "-q", "-m", "a doc becomes code")
        moved = git(repo, "rev-parse", "HEAD")
        check(sorted(snapshot.changed_files(repo, moved) or []) == ["a.kt", "docs/a.md"],
              "a rename out of docs is read as its new path alone, or not at all")
        check(refused(snapshot.checks_run, "o/veyq", moved, "main", repo),
              "a commit that moves a doc into code is skipped as docs-only")
    finally:
        snapshot._gh_json = real_gh

# ---- the staged bytes are the run's -------------------------------------------------------------------------------
staged_digests = {(i, a): ("sha256:" + (str(n) * 64)) for n, (i, a) in enumerate(
    ((i, a) for i in snapshot.BUILD_IMAGES for a in ARCHS), start=1)}
records = [{"arch": a, "shard": d, "images": {i: staged_digests[(i, a)] for i in snapshot.LANE_IMAGES}}
           for a in ARCHS for d in DOMAINS]
check(not refused(snapshot.staged_verdict, staged_digests, records, dict(staged_digests), ARCHS),
      "the staged bytes of the run are refused")
moved = dict(staged_digests); moved[("veyq", "arm64")] = D4
check(refused(snapshot.staged_verdict, staged_digests, records, moved, ARCHS), "a moved staging tag is published")
gone = dict(staged_digests); gone[("veyq-tools", "amd64")] = None
check(refused(snapshot.staged_verdict, staged_digests, records, gone, ARCHS), "a deleted staging tag is published")
partial = {k: v for k, v in staged_digests.items() if k != ("veyq-admin", "amd64")}
check(refused(snapshot.staged_verdict, partial, records, dict(staged_digests), ARCHS),
      "an image the run did not stage is published")
foreign = [dict(r, images=dict(r["images"], veyq=D3)) if r["shard"] == "account" else r for r in records]
check(refused(snapshot.staged_verdict, staged_digests, foreign, dict(staged_digests), ARCHS),
      "a lane record of other bytes is published")
check(refused(snapshot.staged_verdict, staged_digests, [], dict(staged_digests), ARCHS), "no lane record is published")

if failures:
    print("SNAPSHOT SELFTEST FAILED:")
    for message in failures:
        print("  -", message)
    sys.exit(1)
print("snapshot selftest OK: the asset-set hash ignores what differs between productions and sees a changed file; "
      "the predicates refuse a missing, extra, failed, foreign or inconsistent leg; a snapshot name holds only its build; "
      "only the newest, wholly green push run of Checks of HEAD — or of its newest tested ancestor, when every commit after "
      "it changes only what Checks ignores, within the walk's bound — is published from, under that commit's name and "
      "version, and only the bytes it staged and tested")
