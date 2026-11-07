#!/usr/bin/env python3
"""Prove that a published image's labels name the commit it was built at, whatever the build cache held (ADR 0057).

`scripts/validate_release_identity.py` assertion 12 holds the Dockerfiles and `pipeline.yml` to setting the identity labels
from the stamps in the target stage, and to passing no label of their own. That is sound only on BuildKit's premise
that a `LABEL` is image config and never a cached layer. This proof measures the premise, with the host's Docker and
no registry: for each image of `pipeline.yml`'s `build` matrix (or the ones `--image` names) it builds the target three
times, as the `build` job's local step does, at two revisions — the parent commit's and HEAD's `sha-<sha>`, the same
tree, so the second and third build find every layer in the cache:

  A   at the parent's revision
  B   at HEAD's revision        — must share A's layers, and carry HEAD's revision
  A'  at the parent's revision  — must be A again, byte for byte (same image id): the cache was warm and reused

Green means, per image: every layer of B is A's (nothing was rebuilt), yet B's `org.opencontainers.image.revision` is
HEAD's and A's the parent's, both `version` labels the version given, A' is A, and the image carries no
`org.opencontainers.image.*` label its target stage does not set itself — one a base image lends (nginx's `licenses`,
Ubuntu's `version`) speaks for the base, not for veyq. `Created` is NOT checked: BuildKit takes it from the cache, so a
fully cached build reports the cache's time, which is what heavy-stage finding B1 read as a stale identity.

    python3 scripts/image_identity_cache_proof.py [--image veyq-admin] [--platform linux/arm64] [--version 0.0-PROOF]

It tags `veyq-identity-proof/<image>:{a,b}` and removes the tags at the end; the layers stay in the build cache, which
is what makes a re-run fast. Needs Docker and PyYAML; never pushes, never pulls more than the Dockerfiles' pinned bases.
Run by hand, never in CI (owner 2026-10-24): it costs three builds per image, and assertion 12 already holds the rule.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import shlex
import subprocess
import sys
import tempfile

try:
    import yaml
except ModuleNotFoundError:  # pragma: no cover - same shape as the guards
    print("PyYAML is required (pip install pyyaml)", file=sys.stderr)
    sys.exit(2)

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/pipeline.yml"
OCI = "org.opencontainers.image."
FROM_LINE = re.compile(r"^FROM\s+\S+(?:\s+--\S+)*(?:\s+AS\s+(\S+))?\s*$", re.IGNORECASE)
ARG_LINE = re.compile(r"^ARG\s+(.+?)\s*$", re.IGNORECASE)
LABEL_LINE = re.compile(r"^LABEL\s+(.*)$", re.IGNORECASE | re.DOTALL)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def matrix() -> list[dict]:
    jobs = (yaml.safe_load(WORKFLOW.read_text()) or {}).get("jobs") or {}
    include = ((jobs.get("build") or {}).get("strategy") or {}).get("matrix", {}).get("include") or []
    return [e for e in include if isinstance(e, dict) and "dockerfile" in e]


def target_stage(dockerfile: pathlib.Path, target: str) -> tuple[set[str], set[str]]:
    """The ARG names and the label keys the target stage declares, continuation lines joined, comments dropped."""
    instructions: list[str] = []
    pending = ""
    for line in dockerfile.read_text().splitlines():
        if line.strip().startswith("#") or (not line.strip() and not pending):
            continue
        if line.rstrip().endswith("\\"):
            pending += line.rstrip()[:-1] + " "
            continue
        instructions.append((pending + line).strip())
        pending = ""
    current, args, labels = None, set(), set()
    for instruction in instructions:
        start = FROM_LINE.match(instruction)
        if start:
            current = start.group(1)
            continue
        if current != target:
            continue
        if m := ARG_LINE.match(instruction):
            args.update(token.split("=", 1)[0] for token in m.group(1).split())
        elif m := LABEL_LINE.match(instruction):
            labels.update(pair.partition("=")[0] for pair in shlex.split(m.group(1)))
    return args, labels


def build(entry: dict, platform: str | None, version: str, revision: str, args: set[str], tag: str) -> dict:
    """One build as the `build` job's local step runs it; returns the image's inspect record."""
    with tempfile.TemporaryDirectory(prefix="identity_proof_") as tmp:
        iid = pathlib.Path(tmp) / "iid"
        cmd = ["docker", "build", "--file", str(entry["dockerfile"]), "--target", str(entry["target"]),
               "--build-arg", f"VEYQ_VERSION={version}", "--build-arg", f"VEYQ_REVISION={revision}",
               "--iidfile", str(iid), "--tag", tag]
        if "VEYQ_MAJOR_ENTRY" in args:
            cmd += ["--build-arg", "VEYQ_MAJOR_ENTRY="]
        if platform:
            cmd += ["--platform", platform]
        print("+", " ".join(shlex.quote(c) for c in cmd), flush=True)
        subprocess.run([*cmd, "."], cwd=ROOT, check=True)
        image_id = iid.read_text().strip()
    return json.loads(subprocess.run(["docker", "image", "inspect", image_id], check=True, capture_output=True,
                                     text=True).stdout)[0]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--image", action="append", help="a matrix image to prove (default: every one)")
    parser.add_argument("--platform", help="as the build job's `--platform` (default: the host's)")
    parser.add_argument("--version", default="0.0-PROOF", help="the VEYQ_VERSION stamp of all three builds")
    opts = parser.parse_args()

    head, parent = git("rev-parse", "HEAD"), git("rev-parse", "HEAD^")
    revisions = {"a": f"sha-{parent}", "b": f"sha-{head}"}
    entries = [e for e in matrix() if not opts.image or e.get("image") in opts.image]
    if not entries:
        print(f"no image of {WORKFLOW.relative_to(ROOT)}'s build matrix matches {opts.image} — this proved NOTHING")
        return 2
    problems: list[str] = []
    tags: list[str] = []
    try:
        for entry in entries:
            image = str(entry["image"])
            args, own = target_stage(ROOT / str(entry["dockerfile"]), str(entry["target"]))
            built = {}
            for run, rev in (("a", revisions["a"]), ("b", revisions["b"]), ("a2", revisions["a"])):
                tag = f"veyq-identity-proof/{image}:{run[0]}"
                tags.append(tag)
                built[run] = build(entry, opts.platform, opts.version, rev, args, tag)
            a, b, a2 = built["a"], built["b"], built["a2"]

            def label(record: dict, key: str) -> str | None:
                return ((record.get("Config") or {}).get("Labels") or {}).get(OCI + key)

            found: list[str] = []
            if a2["Id"] != a["Id"]:
                found.append(f"the third build at the parent's revision is {a2['Id'][:19]}, not the first's "
                             f"{a['Id'][:19]} — the cache was not reused, so this is no warm-cache proof")
            if b["RootFS"]["Layers"] != a["RootFS"]["Layers"]:
                found.append("HEAD's build rebuilt a layer — the cache was not warm for it, so this is no warm-cache "
                             "proof (an ARG declared before a RUN puts the stamp into that layer's cache key)")
            for run, record, rev in (("parent", a, revisions["a"]), ("HEAD", b, revisions["b"]),
                                     ("parent again", a2, revisions["a"])):
                if label(record, "revision") != rev:
                    found.append(f"the {run} build is labelled revision `{label(record, 'revision')}`, not `{rev}`")
                if label(record, "version") != opts.version:
                    found.append(f"the {run} build is labelled version `{label(record, 'version')}`, "
                                 f"not `{opts.version}`")
            if a["Id"] == b["Id"]:
                found.append("the builds at two revisions are one image — the label did not follow the stamp")
            lent = sorted(k for k, v in ((b.get("Config") or {}).get("Labels") or {}).items()
                          if k.startswith(OCI) and k not in own and v)
            if lent:
                found.append(f"it carries {', '.join(f'`{k}`' for k in lent)}, which its target stage does not "
                             f"set — a label the base image lends")
            print(f"{'ok  ' if not found else 'FAIL'} {image}: layers shared {len(b['RootFS']['Layers'])}, "
                  f"revision {label(a, 'revision')} → {label(b, 'revision')}, id {a['Id'][7:19]} / "
                  f"{b['Id'][7:19]} / {a2['Id'][7:19]}, Created {a['Created'][:19]} / {b['Created'][:19]}", flush=True)
            problems += [f"{image}: {f}" for f in found]
    finally:
        if tags:
            subprocess.run(["docker", "image", "rm", *sorted(set(tags))], capture_output=True)
    if problems:
        print("IMAGE IDENTITY PROOF FAILED:")
        for p in problems:
            print("  -", p)
        return 1
    print(f"image identity proof OK: {len(entries)} image(s), each built at {revisions['a']} and {revisions['b']} on a "
          f"warm cache, every layer shared, each label naming the revision it was built at, and no OCI label lent "
          f"by a base")
    return 0


if __name__ == "__main__":
    sys.exit(main())
