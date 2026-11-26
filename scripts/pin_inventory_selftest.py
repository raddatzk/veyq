#!/usr/bin/env python3
"""Offline self-test of pin_inventory.py: synthetic trees against synthetic Renovate reports.

    python3 scripts/pin_inventory_selftest.py

What it holds, each a case below:
  - a tree whose every pin, of every shape, is extracted is GREEN;
  - a pin of each shape whose extraction is removed from the report is RED, naming its file and line;
  - a pin whose only extraction carries a `skipReason` is RED, naming the reason;
  - a pin with no version is RED whatever the report says (a bare `pip install`, a `FROM` with neither tag nor digest,
    an `npm install -g` without `@x.y.z`, a `uses:` without `@ref`, a requirement without `==`, a `*-version:` of
    `latest`, a dependency of `*`, a non-hashed `pip install -r`);
  - a derived pin (a workflow's `X_SHA256`, the wrapper's `distributionSha256Sum`, a `.pin` file's `commit` and `image`,
    a requirements file `Written by` a script) is GREEN when its partner is extracted and RED when it is not;
  - three pins of one value in one file need three extractions;
  - what is no pin stays out: a stage alias, `scratch`, an expression, `runs-on:`, a local action, a `workspace:`
    dependency, a reference in the docs;
  - an empty report and an empty tree are RED (compared nothing);
  - `--renovate-image` reads renovate.yml's `renovate-version` and refuses one without a digest;
  - every shape still finds a pin in THIS tree, so a shape whose spelling the tree left is not silently dead.

Every digest here is built at run time, so this file holds no `repo:tag@sha256:<64 hex>` of its own for the
inventory to find.
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import contextlib
import copy
import io
import json
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import pin_inventory as inv  # noqa: E402

D1 = "sha256:" + "a" * 64
D2 = "sha256:" + "b" * 64
D3 = "sha256:" + "c" * 64
D4 = "sha256:" + "f" * 64
D_DOCS = "sha256:" + "9" * 64
D_SITE = "sha256:" + "8" * 64
SHA = "1" * 40
SHA_B = "2" * 40
HASH = "d" * 64
HASH_B = "e" * 64

TREE = {
    ".github/workflows/ci.yml": f"""\
name: CI
env:
  HELM_VERSION: v4.3.2
  HELM_SHA256: {HASH}
  APP_VERSION: ${{{{ needs.version.outputs.version }}}}
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@{SHA} # v7.0.1
      - uses: ./.github/actions/local
      - uses: docker://busybox:1.38.0@{D3}
      - name: Set up Node
        uses: actions/setup-node@{SHA_B} # v7.1.0
        with:
          node-version: "22"
      - uses: sigstore/cosign-installer@{SHA_B}
        with:
          cosign-release: v3.1.3
      - uses: sigstore/cosign-installer@{SHA_B}
        with:
          cosign-release: v3.1.3
      - run: npm install -g pnpm@12.16.1
      - name: Python
        run: |
          # pip install pyyaml   (a comment, no command)
          "$RUNNER_TEMP/venv/bin/pip" install --quiet --require-hashes --only-binary :all: -r scripts/requirements-release.txt
          echo "with: node-version: latest"
""",
    "Dockerfile": f"""\
FROM node:22-bookworm-slim@{D1} AS build
RUN npm install -g pnpm@12.16.1
FROM build AS test
FROM scratch AS empty
ARG VEYQ_VERSION=""
ENV VEYQ_VERSION=${{VEYQ_VERSION}}
FROM python:3.13-slim@{D2}
RUN python -m pip install --require-hashes --only-binary :all: \\
    -r requirements.txt
""",
    "compose.yml": f"services:\n  db:\n    image: mariadb:12.3@{D4}\n"
                   "  app:\n    image: ${VEYQ_IMAGE:?set it}\n",
    "src/tool.py": f'DB_IMAGE = "mariadb:12.3@{D4}"\n',
    "docs/notes.md": f"An example: mariadb:12.3@{D_DOCS}\n",
    "site/page.txt": f"An example: mariadb:12.3@{D_SITE}\n",
    "package.json": json.dumps({
        "packageManager": "pnpm@12.16.1",
        "dependencies": {"@veyq/crypto": "workspace:*", "vue": "^3.5.0"},
        "devDependencies": {"vitest": "^5.1.1"},
        "engines": {"node": ">=22"},
    }, indent=2),
    "gradle/wrapper/gradle-wrapper.properties": f"distributionSha256Sum={HASH}\n"
        "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.9.0-bin.zip\n",
    "gradle/libs.versions.toml": """\
[versions]
kotlin = "2.4.21"
[libraries]
kotlin-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
bom-managed = { module = "io.quarkus:quarkus-rest" }
generator = "org.openapitools:openapi-generator:7.23.0"
""",
    "build.gradle.kts": 'plugins {\n    id("info.solidsoft.pitest") version "1.19.0"\n}\n'
                        'dependencies {\n    api("software.amazon.awssdk:s3:2.59.3")\n    api("io.quarkus:quarkus-arc")\n}\n',
    "scripts/requirements-release.txt": f"# hashed\nPyYAML==6.0.3 \\\n    --hash=sha256:{HASH}\n",
    "requirements.txt": f"# Written by ./lock.py, never by hand.\nanyio==4.15.1 \\\n    --hash=sha256:{HASH_B}\n",
    "lock.py": 'SCHEMATHESIS = "schemathesis==4.32.0"\n',
    "webp/libwebp.pin": f"# comment\ntag v1.6.0\ncommit {SHA}\nimage python:3.13-slim@{D2}\nsha256 {HASH} out.wasm\n",
    "openapitools.json": json.dumps({"generator-cli": {"version": "7.23.0"}}, indent=2),
}


def dep(name, value=None, digest=None, skip=None):
    d = {"depName": name}
    if value is not None:
        d["currentValue"] = value
    if digest is not None:
        d["currentDigest"] = digest
    if skip is not None:
        d["skipReason"] = skip
    return d


EXTRACTED = {
    "github-actions": {".github/workflows/ci.yml": [
        dep("actions/checkout", "v7.0.1", SHA),
        dep("busybox", "1.38.0", D3),
        dep("actions/setup-node", "v7.1.0", SHA_B),
        dep("sigstore/cosign-installer", "v4.1.3", SHA_B),
        dep("sigstore/cosign-installer", "v4.1.3", SHA_B),
        dep("node", "22"),
        dep("sigstore/cosign", "v3.1.3"),
        dep("sigstore/cosign", "v3.1.3"),
        dep("ubuntu", "latest", skip="invalid-version"),
    ]},
    "regex": {
        ".github/workflows/ci.yml": [dep("helm", "v4.3.2"), dep("pnpm", "12.16.1")],
        "Dockerfile": [dep("pnpm", "12.16.1")],
        "src/tool.py": [dep("mariadb", "12.3", D4)],
        "lock.py": [dep("schemathesis", "4.32.0")],
        "webp/libwebp.pin": [dep("libwebp", "v1.6.0")],
        "openapitools.json": [dep("org.openapitools:openapi-generator-cli", "7.23.0")],
    },
    "dockerfile": {"Dockerfile": [dep("node", "22-bookworm-slim", D1), dep("python", "3.13-slim", D2)]},
    "docker-compose": {"compose.yml": [dep("mariadb", "12.3", D4), dep(None, skip="contains-variable")]},
    "npm": {"package.json": [dep("pnpm", "12.16.1"), dep("@veyq/crypto", "workspace:*", skip="unspecified-version"),
                             dep("vue", "^3.5.0"), dep("vitest", "^5.1.1"), dep("node", ">=22")]},
    "gradle-wrapper": {"gradle/wrapper/gradle-wrapper.properties": [dep("gradle", "9.9.0")]},
    "gradle": {
        "gradle/libs.versions.toml": [dep("org.jetbrains.kotlin:kotlin-gradle-plugin", "2.4.21"),
                                      dep("org.openapitools:openapi-generator", "7.23.0")],
        "build.gradle.kts": [dep("info.solidsoft.pitest", "1.19.0"), dep("software.amazon.awssdk:s3", "2.59.3")],
    },
    "pip_requirements": {"scripts/requirements-release.txt": [dep("PyYAML", "==6.0.3")]},
}


def report_of(extracted):
    package_files = {}
    for manager, files in extracted.items():
        package_files[manager] = [{"packageFile": path, "deps": deps} for path, deps in files.items()]
    return {"problems": [], "repositories": {"local": {"packageFiles": package_files}}}


def run(tree, extracted, extra_args=()):
    """(exit code, stdout) of pin_inventory.main on `tree` against a report of `extracted`."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp, "tree")
        for path, text in tree.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        root.mkdir(exist_ok=True)
        report = pathlib.Path(tmp, "report.json")
        report.write_text(json.dumps(report_of(extracted)), encoding="utf-8")
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            try:
                code = inv.main(["--root", str(root), "--report", str(report), *extra_args])
            except SystemExit as stop:
                code = stop.code if isinstance(stop.code, int) else 1
                out.write(str(stop.code))
        return code, out.getvalue()


def without(extracted, manager, path, predicate):
    copied = copy.deepcopy(extracted)
    copied[manager][path] = [d for d in copied[manager][path] if not predicate(d)]
    return copied


failures: list[str] = []


def expect(label, result, code, *needles):
    got_code, output = result
    if got_code != code:
        failures.append(f"{label}: exit {got_code}, expected {code}\n{output}")
        return
    for needle in needles:
        if needle not in output:
            failures.append(f"{label}: output lacks {needle!r}\n{output}")


def main() -> int:
    expect("every pin reached is green", run(TREE, EXTRACTED), 0, "every one reached")

    # A pin of each shape, its extraction removed: red, at its line.
    removals = [
        ("uses (commit SHA)", "github-actions", ".github/workflows/ci.yml", lambda d: d.get("currentDigest") == SHA,
         ".github/workflows/ci.yml:10: uses"),
        ("uses docker://", "github-actions", ".github/workflows/ci.yml", lambda d: d.get("currentDigest") == D3,
         ".github/workflows/ci.yml:12: uses"),
        ("with-version node-version", "github-actions", ".github/workflows/ci.yml", lambda d: d["depName"] == "node",
         ".github/workflows/ci.yml:16: with-version"),
        ("env-version HELM_VERSION", "regex", ".github/workflows/ci.yml", lambda d: d["depName"] == "helm",
         ".github/workflows/ci.yml:3: env-version"),
        ("npm-global in a workflow", "regex", ".github/workflows/ci.yml", lambda d: d["depName"] == "pnpm",
         ".github/workflows/ci.yml:23: npm-global"),
        ("npm-global in a Dockerfile", "regex", "Dockerfile", lambda d: d["depName"] == "pnpm", "Dockerfile:2: npm-global"),
        ("from", "dockerfile", "Dockerfile", lambda d: d["depName"] == "node", "Dockerfile:1: from"),
        ("digest-ref in a compose file", "docker-compose", "compose.yml", lambda d: d["depName"] == "mariadb",
         "compose.yml:3: digest-ref"),
        ("digest-ref in a Python constant", "regex", "src/tool.py", lambda d: True, "src/tool.py:1: digest-ref"),
        ("package-manager", "npm", "package.json", lambda d: d["depName"] == "pnpm", "package-manager"),
        ("package-dep", "npm", "package.json", lambda d: d["depName"] == "vue", "package-dep `vue: ^3.5.0`"),
        ("gradle-wrapper", "gradle-wrapper", "gradle/wrapper/gradle-wrapper.properties", lambda d: True,
         "gradle-wrapper.properties:2: gradle-wrapper"),
        ("gradle catalog [versions]", "gradle", "gradle/libs.versions.toml", lambda d: "kotlin" in d["depName"],
         "libs.versions.toml:2: gradle"),
        ("gradle catalog coordinate", "gradle", "gradle/libs.versions.toml", lambda d: "openapi" in d["depName"],
         "libs.versions.toml:6: gradle"),
        ("gradle plugin version", "gradle", "build.gradle.kts", lambda d: "pitest" in d["depName"], "build.gradle.kts:2: gradle"),
        ("gradle coordinate in a script", "gradle", "build.gradle.kts", lambda d: "awssdk" in d["depName"],
         "build.gradle.kts:5: gradle"),
        ("requirements", "pip_requirements", "scripts/requirements-release.txt", lambda d: True,
         "requirements-release.txt:2: requirements"),
        ("py-requirement", "regex", "lock.py", lambda d: True, "lock.py:1: py-requirement"),
        ("pin-file", "regex", "webp/libwebp.pin", lambda d: True, "libwebp.pin:2: pin-file"),
        ("openapi-generator", "regex", "openapitools.json", lambda d: True, "openapitools.json:3: openapi-generator"),
    ]
    for label, manager, path, predicate, needle in removals:
        expect(f"unreached {label} is red", run(TREE, without(EXTRACTED, manager, path, predicate)), 1, needle,
               "no Renovate manager extracts it")

    # Derived pins follow their partner: the partner unreached, the derived pin is red too.
    expect("HELM_SHA256 is red with HELM_VERSION unreached",
           run(TREE, without(EXTRACTED, "regex", ".github/workflows/ci.yml", lambda d: d["depName"] == "helm")), 1,
           ".github/workflows/ci.yml:4: env-version `HELM_SHA256", "derived from `HELM_VERSION` in this file")
    expect("distributionSha256Sum is red with the wrapper unreached",
           run(TREE, without(EXTRACTED, "gradle-wrapper", "gradle/wrapper/gradle-wrapper.properties", lambda d: True)), 1,
           "gradle-wrapper.properties:1: gradle-wrapper", "derived from `url`")
    expect("a .pin commit is red with its tag unreached",
           run(TREE, without(EXTRACTED, "regex", "webp/libwebp.pin", lambda d: True)), 1, "libwebp.pin:3: pin-file `commit")
    expect("a .pin image is red with the FROM it copies unreached",
           run(TREE, without(EXTRACTED, "dockerfile", "Dockerfile", lambda d: d["depName"] == "python")), 1,
           "libwebp.pin:4: pin-file `image", "derived from")
    expect("a written requirements file is red with its writer's pin unreached",
           run(TREE, without(EXTRACTED, "regex", "lock.py", lambda d: True)), 1, "requirements.txt:1: requirements",
           "derived from the pins of lock.py")
    no_helm = dict(TREE)
    no_helm[".github/workflows/ci.yml"] = TREE[".github/workflows/ci.yml"].replace("  HELM_VERSION: v4.3.2\n", "")
    expect("a SHA-256 with no version beside it is red", run(no_helm, EXTRACTED), 1, "which is no pin found")

    # skipReason: an extraction that skips the pin does not reach it.
    skipped = copy.deepcopy(EXTRACTED)
    skipped["regex"][".github/workflows/ci.yml"] = [dep("helm", "v4.3.2", skip="github-token-required"), dep("pnpm", "12.16.1")]
    expect("a pin extracted only with a skipReason is red", run(TREE, skipped), 1,
           ".github/workflows/ci.yml:3", "skips it (regex: github-token-required)")
    both = copy.deepcopy(EXTRACTED)
    both["regex"][".github/workflows/ci.yml"].append(dep("helm", "v4.3.2", skip="invalid-value"))
    expect("a pin extracted twice, one without a skipReason, is green", run(TREE, both), 0)

    # Counting: two cosign-release v3.1.3, one extraction.
    one_cosign = copy.deepcopy(EXTRACTED)
    one_cosign["github-actions"][".github/workflows/ci.yml"].remove(dep("sigstore/cosign", "v3.1.3"))
    expect("two pins of one value need two extractions", run(TREE, one_cosign), 1,
           ".github/workflows/ci.yml:22: with-version", "extracted 1 of the 2")

    # Versionless pins: red whatever the report says.
    versionless = [
        ("a bare pip install", ".github/workflows/ci.yml", "      - run: pip install pyyaml\n", "pip install pyyaml"),
        ("a pip install -r without hashes", ".github/workflows/ci.yml",
         "      - run: pip install -r requirements-dev.txt\n", "without hashes"),
        ("an npm install -g without a version", ".github/workflows/ci.yml", "      - run: npm install -g pnpm\n",
         "npm install -g pnpm`"),
        ("a uses without @ref", ".github/workflows/ci.yml", "      - uses: actions/checkout\n", "uses `actions/checkout`"),
        ("a with-version of latest", ".github/workflows/ci.yml",
         "      - uses: actions/setup-node@" + SHA_B + "\n        with:\n          node-version: latest\n",
         "node-version: latest"),
        ("a FROM with neither tag nor digest", "Dockerfile", "FROM ubuntu\n", "from `ubuntu`: no version"),
        ("a requirement without ==", "scripts/requirements-release.txt", "requests>=2\n", "requests>=2"),
    ]
    for label, path, addition, needle in versionless:
        tree = dict(TREE)
        tree[path] = TREE[path] + addition
        expect(f"{label} is red", run(tree, EXTRACTED), 1, needle)
    star = dict(TREE)
    star["package.json"] = json.dumps({"packageManager": "pnpm@12.16.1", "dependencies": {"vue": "^3.5.0", "left-pad": "*"},
                                       "devDependencies": {"vitest": "^5.1.1"}})
    expect("a dependency of * is red", run(star, EXTRACTED), 1, "left-pad: *`: no version")
    latest_env = dict(TREE)
    latest_env[".github/workflows/ci.yml"] = TREE[".github/workflows/ci.yml"].replace("HELM_VERSION: v4.3.2", "HELM_VERSION: latest")
    expect("an env *_VERSION of latest is red", run(latest_env, EXTRACTED), 1, "HELM_VERSION: latest`: no version")

    # What is no pin stays out: the green run above already holds a stage alias, scratch, an expression, runs-on,
    # a local action, a workspace: dependency and references under docs/ and site/. Listing proves none was counted.
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        for path, text in TREE.items():
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text(text, encoding="utf-8")
        pins = inv.inventory(root)
        texts = [p.text for p in pins]
        for stage in ("build", "scratch"):
            if stage in texts:
                failures.append(f"the FROM {stage!r} counted as a pin")
        for not_a_pin in ("ubuntu-latest", "./.github/actions/local", "@veyq/crypto", "APP_VERSION", "VEYQ_VERSION",
                          "VEYQ_IMAGE", D_DOCS, D_SITE, "pip install pyyaml", "node: >=22",
                          "quarkus-rest", "quarkus-arc", "node-version: latest"):
            hits = [t for t in texts if not_a_pin in t]
            if hits:
                failures.append(f"{not_a_pin!r} counted as a pin: {hits}")
        if any(p.path.startswith(("docs/", "site/")) for p in pins):
            failures.append("a reference under docs/ or site/ counted as a pin")

    # Compared nothing is red.
    expect("an empty report is red", run(TREE, {}), 1, "extracted no dependency")
    expect("an empty tree is red", run({}, EXTRACTED), 1, "no pin found")

    # --renovate-image: one `renovate-version: <v>@sha256:<d>` in renovate.yml.
    good = {inv.RENOVATE_WORKFLOW: f"jobs:\n  r:\n    steps:\n      - uses: renovatebot/github-action@{SHA}\n"
                                   f"        with:\n          renovate-version: 44.257.9@{D1}\n"}
    expect("--renovate-image prints the pinned image", run(good, EXTRACTED, ["--renovate-image"]), 0,
           f"ghcr.io/renovatebot/renovate:44.257.9@{D1}")
    bad = {inv.RENOVATE_WORKFLOW: good[inv.RENOVATE_WORKFLOW].replace(f"@{D1}", "")}
    expect("--renovate-image refuses a version without a digest", run(bad, EXTRACTED, ["--renovate-image"]), 1,
           "expected one `renovate-version")

    # Every shape still finds a pin in this tree: a shape the tree's spelling left would otherwise be dead, and green.
    shapes = {p.shape for p in inv.inventory(inv.ROOT)}
    for shape in ("digest-ref", "from", "uses", "env-version", "with-version", "npm-global", "package-manager",
                  "package-dep", "gradle-wrapper", "gradle", "requirements", "py-requirement", "pin-file",
                  "openapi-generator"):
        if shape not in shapes:
            failures.append(f"the shape {shape!r} finds no pin in this tree: is it still spelled that way?")

    if failures:
        for failure in failures:
            print(f"FAIL {failure}")
        return 1
    print("pin_inventory_selftest: all cases green")
    return 0


if __name__ == "__main__":
    sys.exit(main())
