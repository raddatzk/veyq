#!/usr/bin/env python3
"""Offline self-test of `staging_retention.select()`: which GHCR versions a weekly Publish deletes (ADR 0091
decision 5, amended 2026-11-07). A fake package listing and fake index children; each case names what must go or stay.
`checks.yml`'s `guards` job runs it.

    python3 scripts/staging_retention_selftest.py
"""
from __future__ import annotations

import datetime
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import staging_retention as sr  # noqa: E402

failures: list[str] = []
NOW = datetime.datetime(2026, 12, 31, tzinfo=datetime.timezone.utc)
OLD, NEW = "2026-11-01T00:00:00Z", "2026-12-20T00:00:00Z"
A, B, C, TIP = ("a" * 40), ("b" * 40), ("c" * 40), ("d" * 40)


def dg(n: int) -> str:
    return "sha256:" + f"{n:064x}"


def v(id_: int, digest: str, created: str, *tags: str) -> dict:
    return {"id": id_, "name": digest, "created_at": created, "metadata": {"container": {"tags": list(tags)}}}


VERSIONS = [
    # commit A: old, no snapshot — the staging index and both children go
    v(1, dg(1), OLD, f"build-{A}-amd64"), v(2, dg(2), OLD), v(3, dg(3), OLD),
    # commit B: old, published — its staging index goes, the children stay (the snapshot index names them)
    v(4, dg(4), OLD, f"build-{B}-amd64"), v(5, dg(5), OLD), v(6, dg(6), OLD),
    v(7, dg(7), OLD, f"sha-{B}", "0.12-SNAPSHOT"),
    # commit C: recent — stays
    v(8, dg(8), NEW, f"build-{C}-amd64"), v(9, dg(9), NEW), v(10, dg(10), NEW),
    # the tip of a branch: old, stays
    v(11, dg(11), OLD, f"build-{TIP}-arm64"), v(12, dg(12), OLD),
    # a release's tag beside a staging tag: never touched
    v(13, dg(13), OLD, f"build-{A}-arm64", "0.12"),
    # an untagged version that is no deleted index's child: never touched
    v(14, dg(14), OLD),
    # commit A's arm64 staging index, whose child is ALSO named by a kept index (13): the child stays
    v(15, dg(15), OLD, f"build-{A}-arm64"), v(16, dg(16), OLD),
]
CHILDREN = {dg(1): {dg(2), dg(3)}, dg(4): {dg(5), dg(6)}, dg(7): {dg(5), dg(6)}, dg(8): {dg(9), dg(10)},
            dg(11): {dg(12)}, dg(13): {dg(16)}, dg(15): {dg(16)}}


def children_of(digest: str) -> set[str]:
    return CHILDREN.get(digest, set())


deleted = {d.version_id for d in sr.select(VERSIONS, children_of, {TIP}, NOW, 30)}
want = {1, 2, 3, 4, 15}
for vid in sorted(want - deleted):
    failures.append(f"version {vid} should be deleted and is not")
for vid in sorted(deleted - want):
    failures.append(f"version {vid} is deleted and must stay")
if sr.select(VERSIONS, children_of, {A, B, C, TIP}, NOW, 30):
    failures.append("a branch tip's staging is deleted")
if sr.select(VERSIONS, children_of, {TIP}, NOW, 365):
    failures.append("staging younger than the bound is deleted")
reasons = {d.version_id: d.reason for d in sr.select(VERSIONS, children_of, {TIP}, NOW, 30)}
if "with a snapshot" not in reasons.get(4, ""):
    failures.append(f"the reason does not say a published commit's children stay: {reasons.get(4)}")

if failures:
    print("STAGING RETENTION SELFTEST FAILED:")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print("staging retention selftest OK: an old unpublished staging index goes with its children, a published one alone, "
      "and a branch tip, a recent push, a release's tag, an unrelated untagged version and a child another kept index "
      "names all stay")
