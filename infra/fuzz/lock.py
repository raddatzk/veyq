"""Write `requirements.txt`: Schemathesis and everything it installs, each pinned by version and by wheel hash.

The API fuzzing's image (`./Dockerfile`, ADR 0091 decision 9) installs with `pip install --require-hashes`, so what it
runs is exactly what this file pinned. The resolution is pip's own (`pip install --dry-run --report`, binary wheels
only, in the image's own base so its Python decides the markers); the hashes are PyPI's, for every wheel of each pinned
version that installs on CPython 3.13 on linux/amd64 or linux/arm64 — the two architectures CI runs on — and the
script refuses a package that has no wheel for one of them.

Run it inside the Dockerfile's own base image (the same `FROM`, digest included), from the repository root:

    docker run --rm -v "$PWD/infra/fuzz:/work" --entrypoint python python:3.13-slim@sha256:<the Dockerfile's> /work/lock.py

A Schemathesis bump is `SCHEMATHESIS` below, then this, then a fuzz run (`scripts/run_api_fuzz.py`). Written in the
syntax of the host's Python 3.9, which the repository's tooling reads every tracked `*.py` with.
"""
from __future__ import annotations

import json
import subprocess
import sys
import urllib.request

SCHEMATHESIS = "schemathesis==4.32.0"
HEADER = (
    "# Schemathesis and everything it installs, each pinned to its version and to the SHA-256 of every wheel it may\n"
    "# install on linux/amd64 and linux/arm64 under CPython 3.13 (`pip install --require-hashes` in ./Dockerfile).\n"
    "# Written by ./lock.py, never by hand: a bump re-runs it (its docstring says how).\n"
)


def fits(filename: str) -> bool:
    if filename.endswith("-none-any.whl"):
        return True
    return ("manylinux" in filename and ("x86_64" in filename or "aarch64" in filename)
            and ("-cp313-cp313-" in filename or "-abi3-" in filename))


def arch(filename: str) -> str:
    if filename.endswith("-none-any.whl"):
        return "any"
    return "x86_64" if "x86_64" in filename else "aarch64"


def main() -> int:
    subprocess.run([sys.executable, "-m", "pip", "install", "--quiet", "--dry-run", "--ignore-installed",
                    "--only-binary", ":all:", "--report", "/tmp/report.json", SCHEMATHESIS], check=True)
    with open("/tmp/report.json") as f:
        report = json.load(f)
    blocks = []
    for item in sorted(report["install"], key=lambda i: i["metadata"]["name"].lower()):
        name, version = item["metadata"]["name"].lower(), item["metadata"]["version"]
        with urllib.request.urlopen(f"https://pypi.org/pypi/{name}/{version}/json") as r:
            files = json.load(r)["urls"]
        picked = [f for f in files if f["packagetype"] == "bdist_wheel" and fits(f["filename"])]
        archs = {arch(f["filename"]) for f in picked}
        if "any" not in archs and not {"x86_64", "aarch64"} <= archs:
            raise SystemExit(f"lock: {name}=={version} has no wheel for every architecture ({sorted(archs)})")
        hashes = sorted({f["digests"]["sha256"] for f in picked})
        blocks.append(f"{name}=={version} \\\n" + " \\\n".join(f"    --hash=sha256:{h}" for h in hashes))
    with open("/work/requirements.txt", "w") as f:
        f.write(HEADER + "\n".join(blocks) + "\n")
    print(f"lock: {len(blocks)} packages pinned in requirements.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
