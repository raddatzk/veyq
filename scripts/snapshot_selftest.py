#!/usr/bin/env python3
"""Self-test of a snapshot's pure decisions (ADR 0091 decision 5), offline and Docker-free: the asset-set hash
(`release_assets.asset_set_digest`) and `scripts/snapshot.py`'s lane records, predicates and write-once verdicts.
`ci.yml`'s `guards` job runs it.

    python3 scripts/snapshot_selftest.py

What it cannot see: the registry. Whether Buildx words a missing tag the way `snapshot.NOT_FOUND` expects, and whether
`cosign attest` stores the predicate where the promotion finds it, are a run's to show (inbox #471).
"""
from __future__ import annotations

import hashlib
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

if failures:
    print("SNAPSHOT SELFTEST FAILED:")
    for message in failures:
        print("  -", message)
    sys.exit(1)
print("snapshot selftest OK: the asset-set hash ignores what differs between productions and sees a changed file; "
      "the predicates refuse a missing, extra, failed, foreign or inconsistent leg; a snapshot name holds only its build")
