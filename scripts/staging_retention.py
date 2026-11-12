#!/usr/bin/env python3
"""Delete old staging images from GHCR (ADR 0091 decision 5, amended 2026-11-07; publish.yml's `staging-retention`).

**What it deletes.** Every push of Checks to `main` or `release/<major>.x` stages each image per architecture as
`build-<commit>-<arch>`: an OCI index of two children, the image manifest and its attestation manifest (measured
2026-11-07 on `veyq:build-79ff52e8…-amd64`). Publish names a snapshot from those children (`imagetools create` lists
them in the snapshot's own index), never from the staging index. So a staging version is deleted once it is older than
`--days` (by its package version's `created_at`) and its commit is no branch tip, and its untagged children go with
it unless a version that stays names them — which is how a commit with a snapshot keeps them: the snapshot's own index
(`sha-<commit>`, `<version>-SNAPSHOT`) names both.

A version is a staging version only when every tag it carries is `build-<40 hex>-<arch>`; a version with any other tag
(a snapshot's, a release's, a cosign `sha256-…` tag) is never touched, and nor is an untagged version that is not a
child of a deleted staging index. Every deletion is printed with its reason before it is made (owner 2026-11-07: on
from its first run).

**Why the bound is 30 days.** A Publish reads the Checks run's artifacts (kept 30 days) and its staging images; the
schedule publishes weekly, so a HEAD older than a week either has its snapshot or was refused. A branch tip is kept
whatever its age — a quiet `release/<old>.x` branch's HEAD stays publishable.

    python3 scripts/staging_retention.py --owner <owner> --repository <owner>/<repo> --days 30 --image veyq …

**Its limits.** The deletion is GitHub's REST API under the workflow's `GITHUB_TOKEN`, which GitHub documents as a
public preview that needs the repository to hold the package's Admin role — a run's to show. It reads children with
`docker buildx imagetools inspect --raw`, so an index it cannot read is not deleted (red). The selection is
`select()`'s, which `staging_retention_selftest.py` holds; the API's paging and words are not.
"""
from __future__ import annotations  # `X | None` — the repo's scripts run on Python 3.9

import argparse
import datetime
import json
import re
import subprocess
import sys
from typing import Callable, NamedTuple

STAGING_TAG = re.compile(r"^build-([0-9a-f]{40})-([a-z0-9]+)$")
SNAPSHOT_SHA_TAG = re.compile(r"^sha-([0-9a-f]{40})$")


class Deletion(NamedTuple):
    version_id: int
    digest: str
    reason: str


def _age_days(created_at: str, now: datetime.datetime) -> float:
    stamp = datetime.datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    return (now - stamp).total_seconds() / 86400


def select(versions: list[dict], children_of: Callable[[str], set[str]], tips: set[str], now: datetime.datetime,
           days: float) -> list[Deletion]:
    """The versions to delete, staging indexes first, each with its reason."""
    tags_of = {int(v["id"]): list(((v.get("metadata") or {}).get("container") or {}).get("tags") or []) for v in versions}
    published = {m.group(1) for tags in tags_of.values() for t in tags for m in [SNAPSHOT_SHA_TAG.match(t)] if m}  # reasons only
    doomed: list[dict] = []
    for v in versions:
        tags = tags_of[int(v["id"])]
        matches = [STAGING_TAG.match(t) for t in tags]
        if not tags or not all(matches):
            continue
        commits = {m.group(1) for m in matches if m}
        if commits & tips:
            continue
        if _age_days(str(v["created_at"]), now) < days:
            continue
        doomed.append(v)
    doomed_ids = {int(v["id"]) for v in doomed}
    by_digest = {str(v["name"]): v for v in versions}
    # What every version that stays names: its children may be no deletion's (an index nothing deletes keeps them).
    kept_children: set[str] = set()
    for v in versions:
        if int(v["id"]) not in doomed_ids and tags_of[int(v["id"])]:
            kept_children |= children_of(str(v["name"]))
    out: list[Deletion] = []
    for v in sorted(doomed, key=lambda x: str(x["created_at"])):
        commits = sorted({STAGING_TAG.match(t).group(1) for t in tags_of[int(v["id"])]})
        age = int(_age_days(str(v["created_at"]), now))
        has_snapshot = any(c in published for c in commits)
        why = (f"staging {', '.join(tags_of[int(v['id'])])}, {age} days old, commit "
               f"{'with a snapshot: its children stay, the snapshot names them' if has_snapshot else 'without a snapshot'}")
        out.append(Deletion(int(v["id"]), str(v["name"]), why))
        for child in sorted(children_of(str(v["name"]))):
            cv = by_digest.get(child)
            if cv is None or tags_of[int(cv["id"])] or child in kept_children:
                continue
            out.append(Deletion(int(cv["id"]), child, f"child of the deleted staging index {v['name']}, named by nothing else"))
    return out


def _gh(*args: str) -> str:
    proc = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    if proc.returncode != 0:
        raise SystemExit(f"staging_retention: `gh api {' '.join(args)}` failed: {(proc.stderr or proc.stdout).strip()[-300:]}")
    return proc.stdout


def _paged(path: str) -> list[dict]:
    out: list[dict] = []
    page = 1
    while True:
        chunk = json.loads(_gh(f"{path}{'&' if '?' in path else '?'}per_page=100&page={page}"))
        out.extend(chunk)
        if len(chunk) < 100:
            return out
        page += 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--owner", required=True)
    ap.add_argument("--repository", required=True)
    ap.add_argument("--days", type=float, required=True)
    ap.add_argument("--image", action="append", default=[], required=True)
    args = ap.parse_args()
    now = datetime.datetime.now(datetime.timezone.utc)
    tips = {str(b["commit"]["sha"]) for b in _paged(f"repos/{args.repository}/branches")}
    total = 0
    for image in args.image:
        # `users/…` while the repository is a user's; `orgs/…` after the move to the organisation `veyq` (inbox #471).
        versions = _paged(f"users/{args.owner}/packages/container/{image}/versions")
        cache: dict[str, set[str]] = {}

        def children_of(digest: str) -> set[str]:
            if digest not in cache:
                proc = subprocess.run(["docker", "buildx", "imagetools", "inspect", "--raw",
                                       f"ghcr.io/{args.owner}/{image}@{digest}"], capture_output=True, text=True)
                if proc.returncode != 0:
                    raise SystemExit(f"staging_retention: cannot read {image}@{digest}, so nothing of {image} is deleted: "
                                     f"{proc.stderr.strip()[-200:]}")
                doc = json.loads(proc.stdout)
                cache[digest] = {str(m.get("digest")) for m in doc.get("manifests") or []}
            return cache[digest]

        deletions = select(versions, children_of, tips, now, args.days)
        print(f"{image}: {len(versions)} version(s), {len(deletions)} to delete")
        for d in deletions:
            print(f"  delete {image} version {d.version_id} ({d.digest}): {d.reason}")
            _gh("--method", "DELETE", f"users/{args.owner}/packages/container/{image}/versions/{d.version_id}")  # orgs/ too
        total += len(deletions)
    print(f"staging retention: {total} version(s) deleted, older than {args.days:g} days and no branch tip")
    return 0


if __name__ == "__main__":
    sys.exit(main())
