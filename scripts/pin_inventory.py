#!/usr/bin/env python3
"""Every pin in the tree has an updater: the pins found by shape, compared with what Renovate extracted (ADR 0158 decision 1).

    python3 scripts/pin_inventory.py --report <renovate-report.json>   # red (exit 1) on a pin no manager reaches
    python3 scripts/pin_inventory.py --renovate-image                 # the pinned Renovate image renovate.yml runs

Checks' `pins` job runs Renovate in its extract-only dry run (`RENOVATE_PLATFORM=local`, `RENOVATE_DRY_RUN=extract`,
no token) with a report file, then this script against that report. Renovate's own extraction is the authority for
"reached": a list kept beside renovate.json5 would drift the way dependabot.yml's "Reached by no ecosystem" comments
did, so this script holds no list of pins and no list of exceptions. It finds pins by their SHAPE, from the code, and
asks the report whether a manager extracted each one.

**What it asserts** — one line per problem, exit 1:
  (a) **a pin no manager reaches.** A pin found by shape in file F is reached when an extracted dependency of F, with
      no `skipReason`, carries its version (`currentValue`) or, for a pin with a digest or a commit SHA, its digest
      (`currentDigest`). Counted, not just looked up: three `cosign-release: v3.1.3` in a file need three extractions
      of v3.1.3 there. One textual pin may be extracted twice (a built-in manager that refuses it with a
      `skipReason` AND a custom regex manager that reads it, e.g. renovate.yml's `renovate-version`); it is reached
      when any one extraction has no `skipReason`. An extraction that carries one is named in the message.
  (b) **a pin with no version at all**: a bare `pip install pyyaml`, a `FROM image` with neither tag nor digest, an
      `npm install -g pnpm` without `@x.y.z`, a `uses:` without `@ref`, a requirement line without `==`, a
      `*-version:` input of `latest`, a dependency range of `*`.
  Also red: a report that extracted nothing, or an inventory that found nothing — "compared nothing" is never green.

**The shapes** (ADR 0057 § 6; a shape found in the tree later is added here, never to an exception list):
  - `digest-ref`: `repo:tag@sha256:<64 hex>` anywhere in a tracked file outside the docs (`docs/`, `site/`, `changes/`,
    `wiki/`, `*.md`). A placeholder that is not 64 hex (`sha256:<digest>`) is no reference. A test that needs a forged
    reference builds it at run time, so its source holds none (pin_inventory_selftest.py does).
  - `from`: a Dockerfile's `FROM`; one naming an earlier stage (`AS` alias) or `scratch` (no image) is no pin.
  - `uses`: a workflow's or action's `uses: owner/repo@<sha>` (the SHA is the digest Renovate reports) and
    `uses: docker://image@sha256:…`; a local `./path` action is part of this tree, not a pin.
  - `env-version`: a `*_VERSION` / `*_SHA256` key in a workflow's `env:`, and an `ARG`/`ENV` `*_VERSION=` with a value in
    a Dockerfile. A value that is an expression (`${{ … }}`) or empty is an input from elsewhere, not a pin.
  - `with-version`: a `with:` input whose key ends `-version` or `-release` (setup-node's `node-version`, setup-java's
    `java-version`, cosign-installer's `cosign-release`, renovate.yml's `renovate-version`).
  - `npm-global`: `npm install -g <pkg>@<version>` in a workflow or a Dockerfile (pnpm's install).
  - `package-manager` / `package-dep`: a `package.json`'s `packageManager`, and its dependencies, devDependencies and
    optionalDependencies. A `workspace:`, `file:`, `link:` or `portal:` dependency names a package of this tree.
  - `gradle-wrapper`: `distributionUrl` in every `gradle-wrapper.properties`.
  - `gradle`: a version catalog's (`*.versions.toml`) `[versions]` entries and every `group:name:version` or
    `version = "…"` in it, and `"group:name:version"` / `id("…") version "…"` in a `*.gradle(.kts)`. A coordinate with
    no version takes it from a platform (BOM) and is no pin of its own.
  - `requirements`: each `name==version` of a requirements file (Renovate's pip_requirements file pattern).
  - `py-requirement`: a module constant `NAME = "pkg==version"` in a Python file (tests/fuzz/lock.py's `SCHEMATHESIS`).
  - `pin-file`: a `*.pin` file's lines (libwebp.pin's `tag`).
  - `openapi-generator`: `generator-cli.version` in an `openapitools.json`.
  - `pip-install`: a `pip install` in a workflow or a Dockerfile installs only `-r <hashed file>` (with
    `--require-hashes`, or from a file whose requirements carry `--hash=`), a local path, or `name==version` — the
    last a pin like any other, which no manager reads in a workflow, so it is red as unreached.

**Derived pins** are written from another pin, not looked up, so they count as reached when their partner is:
  - a workflow's `X_SHA256` follows `X_VERSION` in the same file (Helm's tarball hash; pin_follow.py writes it);
  - `distributionSha256Sum` follows `distributionUrl` (Renovate's gradle-wrapper manager writes both);
  - a `*.pin` file's `commit` follows its `tag`, and its `image` line is the Dockerfile `FROM` written by `build.py`,
    the same string: reached when that reference is reached elsewhere. Its `sha256` lines hash the files built here
    (outputs, not inputs);
  - a requirements file whose header says it is `Written by <file>` (tests/fuzz/requirements.txt, written whole by
    lock.py; renovate.json5 ignores it on purpose) follows the pins of that file;
  - the digest beside a tag (`repo:tag@sha256:…`) is one pin with it, extracted as one dependency.
  The JAR hash files (`scripts/*_jar.sha256`) are keyed by a version a manager reads and are held to it by
  validate_build_input_pins.py; they are no pin shape here.

**Not pins.** `runs-on:` labels name GitHub-hosted runner images, which GitHub moves and nobody here can pin. An
image given by a variable with no default (`image: ${VEYQ_IMAGE:?…}`) is the operator's input, not a value of this
tree. An `engines` range says what the package runs on, not what is installed. A `docker run "$IMAGE_REF"` names an
image built here.

**Its limits — what a green run does NOT prove.** That a reached pin is UPDATED: a manager may extract it and a
`packageRule` hold it back for good (renovate.json5's `allowedVersions` say why each does). That every pin has one of
these shapes: a pin written in a shape not listed above is invisible here until the shape is added (an apt package in
a Dockerfile `RUN` has no version and no shape; Renovate's dockerfile manager reports those as `unspecified-version`
and this script does not read them). Matching is per file and by value: an extraction of the same value from another
line of the same file can stand in for a pin, so two pins of one value in one file are told apart only by their count.
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import argparse
import collections
import dataclasses
import json
import os
import pathlib
import re
import shlex
import subprocess
import sys
from typing import Iterable

ROOT = pathlib.Path(__file__).resolve().parent.parent
RENOVATE_WORKFLOW = ".github/workflows/renovate.yml"
# The image renovatebot/github-action runs: it appends `renovate-version` to this repository (renovate.yml says so).
RENOVATE_IMAGE_REPO = "ghcr.io/renovatebot/renovate"

DOC_DIRS = ("docs/", "site/", "changes/", "wiki/")
HEX64 = r"[0-9a-f]{64}"
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
# An image reference: [registry[:port]/]path:tag@sha256:digest. The registry's port is the one `:` allowed before the
# tag, and only when a `/` follows it.
DIGEST_REF_RE = re.compile(
    r"(?<![\w.-])((?:[a-z0-9][a-z0-9.-]*(?::[0-9]+)?/)?[a-z0-9][a-z0-9._-]*(?:/[a-z0-9][a-z0-9._-]*)*)"
    r":([A-Za-z0-9_][A-Za-z0-9._-]*)@(sha256:" + HEX64 + r")(?![0-9a-f])"
)
# Renovate's pip_requirements default `managerFilePatterns`.
REQUIREMENTS_RE = re.compile(r"(^|/)[\w-]*requirements([-._]\w+)?\.(txt|pip)$")
DOCKERFILE_RE = re.compile(r"(^|/)(Dockerfile|Containerfile)([._-][^/]*)?$|\.Dockerfile$")
WORKFLOW_RE = re.compile(r"^\.github/workflows/[^/]+\.ya?ml$|(^|/)action\.ya?ml$")
VERSIONLESS = {"", "*", "x", "latest", "next", "stable", "lts", "lts/*", "current", "node", "nightly", "master", "main"}
LOCAL_PROTOCOLS = ("workspace:", "file:", "link:", "portal:")


@dataclasses.dataclass
class Pin:
    path: str
    line: int
    shape: str
    text: str
    value: str | None = None  # the version, as a manager reports it (`currentValue`)
    digest: str | None = None  # `sha256:…` or a 40-hex commit SHA (`currentDigest`)
    problem: str | None = None  # set: red whatever the report says (no version, not hashed)
    ident: str | None = None  # names the pin for a derived partner in the same file
    partner: tuple | None = None  # derived: ("ident", ident) | ("ref", reference) | ("file", path)

    def where(self) -> str:
        return f"{self.path}:{self.line}"


# ---- reading the tree -----------------------------------------------------------------------------------------------

def tracked_files(root: pathlib.Path) -> list[str]:
    """The tracked files (`git ls-files`); a tree without git (a synthetic one, an export) is walked instead."""
    try:
        out = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], check=True, capture_output=True).stdout
        files = [f for f in out.decode("utf-8", "surrogateescape").split("\0") if f]
        if files:
            return sorted(files)
    except (OSError, subprocess.CalledProcessError):
        pass
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "node_modules")]
        for name in filenames:
            found.append(pathlib.Path(dirpath, name).relative_to(root).as_posix())
    return sorted(found)


def is_doc(path: str) -> bool:
    return path.startswith(DOC_DIRS) or path.endswith(".md")


def read_text(root: pathlib.Path, path: str) -> str | None:
    try:
        return (root / path).read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def strip_value(raw: str) -> str:
    """A YAML scalar's text without a trailing comment and quotes."""
    value = re.sub(r"\s+#.*$", "", raw.strip())
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        value = value[1:-1]
    return value.strip()


def is_comment(line: str) -> bool:
    return line.lstrip().startswith("#")


def logical_lines(text: str) -> list[tuple[int, str]]:
    """Lines joined over a trailing backslash, each with the number of its first line (a shell's or a Dockerfile's)."""
    out: list[tuple[int, str]] = []
    buf: list[str] = []
    start = 0
    for number, line in enumerate(text.splitlines(), 1):
        if not buf:
            start = number
        if line.rstrip().endswith("\\"):
            buf.append(line.rstrip()[:-1])
            continue
        buf.append(line)
        out.append((start, " ".join(part.strip() for part in buf)))
        buf = []
    if buf:
        out.append((start, " ".join(part.strip() for part in buf)))
    return out


def split_image(ref: str) -> tuple[str, str | None, str | None]:
    """`repo[:tag][@digest]` → (repo, tag, digest). A registry port (`host:5000/x`) is not a tag."""
    digest = None
    if "@" in ref:
        ref, digest = ref.split("@", 1)
    tag = None
    head, _, last = ref.rpartition("/")
    if ":" in last:
        last, tag = last.split(":", 1)
    return (f"{head}/{last}" if head else last), tag, digest


def yaml_mappings(text: str) -> Iterable[tuple[int, str, str, str]]:
    """(line, parent key, key, raw value) for each `key: value` of a workflow, read by indentation. Enough for the keys
    this script needs (`env:`, `with:`); block scalars (`run: |`) are skipped whole, so a shell line is never a key."""
    stack: list[tuple[int, str]] = []
    block_indent = None
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        if block_indent is not None:
            if indent > block_indent:
                continue
            block_indent = None
        if is_comment(line):
            continue
        body = line.strip()
        key_indent = indent
        if body.startswith("- "):
            body = body[2:].lstrip()
            key_indent = indent + 2
        while stack and stack[-1][0] >= indent:
            stack.pop()
        match = re.match(r"""^(['"]?)([^'":#][^'":]*?)\1\s*:(?:\s+(.*))?$""", body)
        if not match:
            continue
        key, raw = match.group(2).strip(), (match.group(3) or "")
        parent = stack[-1][1] if stack else ""
        if raw.strip() == "" or raw.strip().startswith("#"):
            stack.append((key_indent, key))
            continue
        if raw.strip()[:1] in "|>":
            block_indent = key_indent  # the scalar's lines sit deeper than its key
        yield number, parent, key, raw


# ---- the shapes -----------------------------------------------------------------------------------------------------

def shape_digest_refs(path: str, text: str) -> list[Pin]:
    if is_doc(path) or path.endswith(".pin"):
        return []
    pins = []
    for number, line in enumerate(text.splitlines(), 1):
        for match in DIGEST_REF_RE.finditer(line):
            pins.append(Pin(path, number, "digest-ref", match.group(0), value=match.group(2), digest=match.group(3)))
    return pins


def image_pin(path: str, number: int, shape: str, ref: str) -> Pin:
    if "$" in ref:
        return Pin(path, number, shape, ref, problem="the image is a variable: no manager can read its version")
    _, tag, digest = split_image(ref)
    if not tag and not digest:
        return Pin(path, number, shape, ref, problem="no version: neither a tag nor a digest")
    return Pin(path, number, shape, ref, value=tag, digest=digest)


def shape_dockerfile(path: str, text: str) -> list[Pin]:
    if not DOCKERFILE_RE.search(path):
        return []
    pins: list[Pin] = []
    stages: set[str] = set()
    for number, line in logical_lines(text):
        if is_comment(line):
            continue
        words = line.split()
        if not words:
            continue
        instruction = words[0].upper()
        if instruction == "FROM":
            args = [w for w in words[1:] if not w.startswith("--")]
            if not args:
                continue
            ref = args[0]
            earlier_stage = ref.lower() in stages
            if len(args) >= 3 and args[1].upper() == "AS":
                stages.add(args[2].lower())
            if earlier_stage or ref == "scratch":
                continue
            pins.append(image_pin(path, number, "from", ref))
        elif instruction in ("ARG", "ENV"):
            for word in words[1:]:
                match = re.match(r"^([A-Z][A-Z0-9_]*_VERSION)=(.*)$", word)
                value = strip_value(match.group(2)) if match else ""
                if value and "$" not in value:  # empty or `${ARG}`: a build argument's input, not a pin
                    pin = Pin(path, number, "env-version", word, value=value, ident=match.group(1))
                    if value.lower() in VERSIONLESS or not re.search(r"\d", value):
                        pin.problem = "no version"
                    pins.append(pin)
    return pins


def shape_workflow(path: str, text: str) -> list[Pin]:
    if not WORKFLOW_RE.search(path):
        return []
    pins: list[Pin] = []
    for number, parent, key, raw in yaml_mappings(text):
        value = strip_value(raw)
        if "${{" in value:
            continue  # an expression: the value is read from elsewhere, not pinned on this line
        if key == "uses":
            pins.extend(uses_pins(path, number, value))
        elif parent == "env" and re.match(r"^[A-Z][A-Z0-9_]*_(VERSION|SHA256)$", key):
            if not value:
                continue
            if key.endswith("_SHA256"):
                partner = key[: -len("_SHA256")] + "_VERSION"
                pins.append(Pin(path, number, "env-version", f"{key}: {value}", partner=("ident", partner)))
                if not re.fullmatch(HEX64, value):
                    pins[-1].problem = "not a SHA-256"
            else:
                pin = Pin(path, number, "env-version", f"{key}: {value}", value=value, ident=key)
                if value.lower() in VERSIONLESS or not re.search(r"\d", value):
                    pin.problem = "no version"
                pins.append(pin)
        elif parent == "with" and re.search(r"-(version|release)$", key):
            version, _, digest = value.partition("@")
            pin = Pin(path, number, "with-version", f"{key}: {value}", value=version, digest=digest or None)
            if version.lower() in VERSIONLESS or not re.search(r"\d", version):
                pin.problem = "no version"
            pins.append(pin)
    return pins


def uses_pins(path: str, number: int, value: str) -> list[Pin]:
    if value.startswith("./") or value.startswith("../"):
        return []
    if value.startswith("docker://"):
        return [image_pin(path, number, "uses", value[len("docker://"):])]
    if "@" not in value:
        return [Pin(path, number, "uses", value, problem="no version: `uses:` without `@<sha>`")]
    _, ref = value.rsplit("@", 1)
    if SHA_RE.match(ref):
        return [Pin(path, number, "uses", value, digest=ref)]
    return [Pin(path, number, "uses", value, value=ref)]


def command_lines(path: str, text: str) -> list[tuple[int, str]]:
    """The lines a workflow's `run:` or a Dockerfile's `RUN` could execute: every logical line that is no comment."""
    if not (WORKFLOW_RE.search(path) or DOCKERFILE_RE.search(path)):
        return []
    return [(n, line) for n, line in logical_lines(text) if line.strip() and not is_comment(line)]


def shell_words(command: str) -> list[str]:
    try:
        return shlex.split(command, comments=True)
    except ValueError:
        return command.split()


SHELL_ENDS = {"&&", "||", ";", "|", "then", "do", "fi", "done"}
PIP_OPTIONS_WITH_ARG = {
    "-i", "--index-url", "--extra-index-url", "-f", "--find-links", "-t", "--target", "--prefix", "--root",
    "--report", "--platform", "--python-version", "--implementation", "--abi", "--only-binary", "--no-binary",
    "-c", "--constraint", "--progress-bar", "--log", "--cache-dir", "--src", "--upgrade-strategy", "--trusted-host",
}


def shape_installs(root: pathlib.Path, path: str, text: str) -> list[Pin]:
    pins: list[Pin] = []
    for number, line in command_lines(path, text):
        words = shell_words(line)
        for index, word in enumerate(words):
            nxt = words[index + 1] if index + 1 < len(words) else ""
            if re.search(r"(^|/)pip3?$", word) and nxt == "install":
                pins.extend(pip_pins(root, path, number, words[index + 2:]))
            elif word == "npm" and nxt in ("install", "i", "add"):
                pins.extend(npm_global_pins(path, number, words[index + 2:]))
    return pins


def until_shell_end(words: list[str]) -> list[str]:
    out = []
    for word in words:
        if word in SHELL_ENDS or word.endswith(";"):
            if word.endswith(";") and word != ";":
                out.append(word[:-1])
            break
        out.append(word)
    return out


def pip_pins(root: pathlib.Path, path: str, number: int, words: list[str]) -> list[Pin]:
    args = until_shell_end(words)
    hashed = "--require-hashes" in args
    pins: list[Pin] = []
    skip = False
    for index, arg in enumerate(args):
        if skip:
            skip = False
            continue
        if arg in ("-r", "--requirement"):
            skip = True
            target = args[index + 1] if index + 1 < len(args) else ""
            if not (hashed or requirements_hashed(root, path, target)):
                pins.append(Pin(path, number, "pip-install", f"pip install -r {target}",
                                problem=f"installs `{target}` without hashes (`--require-hashes`)"))
            continue
        if arg in PIP_OPTIONS_WITH_ARG:
            skip = True
            continue
        if arg.startswith("-"):
            continue
        if arg.startswith((".", "/", "$")) or "/" in arg:
            continue  # a local path: this tree, or a file the step itself built
        match = re.match(r"^([A-Za-z0-9_.\[\],-]+)==([^;\s]+)$", arg)
        if match:
            pins.append(Pin(path, number, "pip-install", f"pip install {arg}", value=match.group(2)))
        else:
            pins.append(Pin(path, number, "pip-install", f"pip install {arg}",
                            problem="no version: install `-r <hashed requirements>` or `name==version`"))
    return pins


def requirements_hashed(root: pathlib.Path, path: str, target: str) -> bool:
    for candidate in (target, str(pathlib.PurePosixPath(path).parent / target)):
        text = read_text(root, candidate) if candidate else None
        if text is not None:
            reqs = [l for _, l in logical_lines(text) if l.strip() and not is_comment(l) and not l.strip().startswith("-")]
            return bool(reqs) and all("--hash=" in l for l in reqs)
    return False


def npm_global_pins(path: str, number: int, words: list[str]) -> list[Pin]:
    args = until_shell_end(words)
    if not any(a in ("-g", "--global") for a in args):
        return []  # a project install reads package.json and its lockfile, whose shapes are below
    pins = []
    for arg in args:
        if arg.startswith("-"):
            continue
        name, _, version = arg[1:].partition("@") if arg.startswith("@") else arg.partition("@")
        if version and re.search(r"\d", version) and version.lower() not in VERSIONLESS:
            pins.append(Pin(path, number, "npm-global", f"npm install -g {arg}", value=version))
        else:
            pins.append(Pin(path, number, "npm-global", f"npm install -g {arg}",
                            problem="no version: install `<package>@<x.y.z>`"))
    return pins


def line_of(text: str, needle: str) -> int:
    for number, line in enumerate(text.splitlines(), 1):
        if needle in line:
            return number
    return 1


def shape_package_json(path: str, text: str) -> list[Pin]:
    if not path.endswith("package.json") or "node_modules/" in path:
        return []
    try:
        data = json.loads(text)
    except ValueError:
        return []
    if not isinstance(data, dict):
        return []
    pins = []
    manager = data.get("packageManager")
    if isinstance(manager, str):
        number = line_of(text, '"packageManager"')
        name, _, version = manager.partition("@")
        version = version.split("+", 1)[0]
        pin = Pin(path, number, "package-manager", manager, value=version)
        if not re.search(r"\d", version):
            pin.problem = "no version: `packageManager` names no `@x.y.z`"
        pins.append(pin)
    for section in ("dependencies", "devDependencies", "optionalDependencies"):
        deps = data.get(section)
        if not isinstance(deps, dict):
            continue
        for name, spec in deps.items():
            if not isinstance(spec, str) or spec.startswith(LOCAL_PROTOCOLS):
                continue
            number = line_of(text, json.dumps(name) + ":")
            pin = Pin(path, number, "package-dep", f"{name}: {spec}", value=spec)
            if spec.strip().lower() in VERSIONLESS:
                pin.problem = "no version"
            pins.append(pin)
    return pins


def shape_gradle_wrapper(path: str, text: str) -> list[Pin]:
    if not path.endswith("gradle-wrapper.properties"):
        return []
    pins = []
    for number, line in enumerate(text.splitlines(), 1):
        if line.startswith("distributionUrl"):
            match = re.search(r"gradle-([^/]+?)-(bin|all)\.zip", line)
            if match:
                pins.append(Pin(path, number, "gradle-wrapper", line.strip(), value=match.group(1), ident="url"))
            else:
                pins.append(Pin(path, number, "gradle-wrapper", line.strip(), problem="no version in distributionUrl"))
        elif line.startswith("distributionSha256Sum"):
            pins.append(Pin(path, number, "gradle-wrapper", line.strip(), partner=("ident", "url")))
    return pins


COORD_RE = re.compile(r'"([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([0-9][A-Za-z0-9_.+-]*)"')


def shape_gradle(path: str, text: str) -> list[Pin]:
    pins = []
    if path.endswith(".versions.toml"):
        section = ""
        for number, line in enumerate(text.splitlines(), 1):
            stripped = line.split("#", 1)[0].strip()
            if not stripped:
                continue
            header = re.match(r"^\[([^\]]+)\]$", stripped)
            if header:
                section = header.group(1)
                continue
            if section == "versions":
                match = re.match(r'^([A-Za-z0-9_.-]+)\s*=\s*(.+)$', stripped)
                if match:
                    raw = match.group(2)
                    found = re.search(r'"([^"]*)"', raw)
                    value = found.group(1) if found else ""
                    pin = Pin(path, number, "gradle", stripped, value=value)
                    if not re.search(r"\d", value):
                        pin.problem = "no version"
                    pins.append(pin)
                continue
            for match in COORD_RE.finditer(stripped):
                pins.append(Pin(path, number, "gradle", match.group(0), value=match.group(3)))
            match = re.search(r'(?<![.\w])version\s*=\s*"([^"]+)"', stripped)
            if match:
                pins.append(Pin(path, number, "gradle", stripped, value=match.group(1)))
    elif path.endswith((".gradle.kts", ".gradle")):
        for number, line in enumerate(text.splitlines(), 1):
            code = line.split("//", 1)[0]
            for match in COORD_RE.finditer(code):
                pins.append(Pin(path, number, "gradle", match.group(0), value=match.group(3)))
            for match in re.finditer(r'id\s*\(?\s*"([^"]+)"\s*\)?\s*version\s*"([^"]+)"', code):
                pins.append(Pin(path, number, "gradle", match.group(0), value=match.group(2)))
    return pins


def shape_requirements(root: pathlib.Path, path: str, text: str, tracked: set[str]) -> list[Pin]:
    if not REQUIREMENTS_RE.search(path):
        return []
    header = []
    for line in text.splitlines():
        if not is_comment(line):
            break
        header.append(line)
    written = re.search(r"Written by (\S+?)[,.;:]?(\s|$)", " ".join(header))
    if written:
        target = written.group(1).strip("`'\"")
        resolved = os.path.normpath(str(pathlib.PurePosixPath(path).parent / target)).replace(os.sep, "/")
        for candidate in (resolved, target):
            if candidate in tracked:
                return [Pin(path, 1, "requirements", f"written by {candidate}", partner=("file", candidate))]
    pins = []
    for number, line in logical_lines(text):
        body = line.split(" #", 1)[0].strip()
        if not body or is_comment(body) or body.startswith("-"):
            continue
        requirement = body.split(" --", 1)[0].split(";", 1)[0].strip()
        match = re.match(r"^([A-Za-z0-9_.\[\],-]+)\s*===?\s*([^\s,]+)$", requirement)
        if match:
            pins.append(Pin(path, number, "requirements", requirement, value=match.group(2)))
        else:
            pins.append(Pin(path, number, "requirements", requirement, problem="no exact version (`name==version`)"))
    return pins


def shape_py_requirement(path: str, text: str) -> list[Pin]:
    if not path.endswith(".py") or is_doc(path):
        return []
    pins = []
    for number, line in enumerate(text.splitlines(), 1):
        match = re.match(r"""^([A-Z][A-Z0-9_]*)\s*=\s*["']([A-Za-z0-9_.\[\],-]+)==([^"';\s]+)["']""", line)
        if match:
            pins.append(Pin(path, number, "py-requirement", f"{match.group(2)}=={match.group(3)}", value=match.group(3)))
    return pins


def shape_pin_file(path: str, text: str) -> list[Pin]:
    if not path.endswith(".pin"):
        return []
    pins = []
    for number, line in enumerate(text.splitlines(), 1):
        if not line.strip() or is_comment(line):
            continue
        key, _, rest = line.strip().partition(" ")
        rest = rest.strip()
        if key == "sha256":
            continue  # the hash of a file built here: an output, not an input
        if key == "commit":
            pins.append(Pin(path, number, "pin-file", line.strip(), partner=("ident", "tag")))
        elif key == "image":
            pins.append(Pin(path, number, "pin-file", line.strip(), partner=("ref", rest)))
        else:
            pin = Pin(path, number, "pin-file", line.strip(), value=rest, ident=key)
            if not rest or not re.search(r"\d", rest):
                pin.problem = "no version"
            pins.append(pin)
    return pins


def shape_openapitools(path: str, text: str) -> list[Pin]:
    if not path.endswith("openapitools.json"):
        return []
    try:
        version = json.loads(text).get("generator-cli", {}).get("version")
    except (ValueError, AttributeError):
        version = None
    number = line_of(text, '"version"')
    if not isinstance(version, str) or not re.search(r"\d", version):
        return [Pin(path, number, "openapi-generator", "generator-cli.version", problem="no version")]
    return [Pin(path, number, "openapi-generator", f"generator-cli.version {version}", value=version)]


def inventory(root: pathlib.Path, exclude: Iterable[str] = ()) -> list[Pin]:
    files = [f for f in tracked_files(root) if f not in set(exclude) and "node_modules/" not in f]
    tracked = set(files)
    pins: list[Pin] = []
    for path in files:
        text = read_text(root, path)
        if text is None:
            continue
        found: list[Pin] = []
        found += shape_dockerfile(path, text)
        found += shape_workflow(path, text)
        found += shape_installs(root, path, text)
        found += shape_package_json(path, text)
        found += shape_gradle_wrapper(path, text)
        found += shape_gradle(path, text)
        found += shape_requirements(root, path, text, tracked)
        found += shape_py_requirement(path, text)
        found += shape_pin_file(path, text)
        found += shape_openapitools(path, text)
        # A digest reference another shape already holds (a FROM, a `uses: docker://`) is that pin, not a second one.
        held = {(p.line, p.digest) for p in found if p.digest}
        found += [p for p in shape_digest_refs(path, text) if (p.line, p.digest) not in held]
        pins += found
    return pins


# ---- the report -----------------------------------------------------------------------------------------------------

def split_value(value: str | None) -> tuple[str | None, str | None]:
    if value is None:
        return None, None
    value = str(value).strip()
    if value.startswith("=="):
        value = value[2:]
    if "@sha256:" in value:
        version, digest = value.split("@", 1)
        return version, digest
    return value, None


def read_report(path: pathlib.Path) -> dict[str, list[dict]]:
    """packageFile → its extracted dependencies, over every manager (custom regex managers report as `regex`)."""
    data = json.loads(path.read_text(encoding="utf-8"))
    by_file: dict[str, list[dict]] = collections.defaultdict(list)
    for repository in (data.get("repositories") or {}).values():
        for manager, package_files in (repository.get("packageFiles") or {}).items():
            for package_file in package_files or []:
                for dep in package_file.get("deps") or []:
                    by_file[package_file.get("packageFile", "")].append(dict(dep, manager=manager))
    return by_file


def dep_keys(dep: dict) -> set[tuple[str, str]]:
    value, digest = split_value(dep.get("currentValue"))
    digest = dep.get("currentDigest") or digest
    keys = set()
    if value:
        keys.add(("v", value))
    if digest:
        keys.add(("d", digest))
    return keys


def pin_key(pin: Pin) -> tuple[str, str]:
    return ("d", pin.digest) if pin.digest else ("v", split_value(pin.value)[0] or "")


def check(pins: list[Pin], extracted: dict[str, list[dict]]) -> list[str]:
    problems: list[str] = []
    reached: dict[int, bool] = {}
    groups: dict[tuple[str, tuple[str, str]], list[Pin]] = collections.defaultdict(list)
    for pin in pins:
        if pin.problem:
            problems.append(f"{pin.where()}: {pin.shape} `{pin.text}`: {pin.problem}")
            reached[id(pin)] = False
        elif pin.partner is None:
            groups[(pin.path, pin_key(pin))].append(pin)
    for (path, key), group in sorted(groups.items(), key=lambda item: (item[0][0], item[1][0].line)):
        usable = [d for d in extracted.get(path, []) if key in dep_keys(d) and not d.get("skipReason")]
        skipped = [d for d in extracted.get(path, []) if key in dep_keys(d) and d.get("skipReason")]
        for index, pin in enumerate(sorted(group, key=lambda p: p.line)):
            ok = index < len(usable)
            reached[id(pin)] = ok
            if ok:
                continue
            if skipped:
                reasons = ", ".join(sorted({f"{d['manager']}: {d['skipReason']}" for d in skipped}))
                why = f"Renovate extracted it but skips it ({reasons})"
            elif usable:
                why = f"Renovate extracted {len(usable)} of the {len(group)} pins of this value in this file"
            else:
                why = "no Renovate manager extracts it"
            problems.append(f"{pin.where()}: {pin.shape} `{pin.text}`: {why}")
    by_ident = {(p.path, p.ident): p for p in pins if p.ident}
    for pin in pins:
        if pin.partner is None or pin.problem:
            continue
        kind, target = pin.partner
        if kind == "ident":
            partners = [by_ident[(pin.path, target)]] if (pin.path, target) in by_ident else []
            name = f"`{target}` in this file"
        elif kind == "ref":
            partners = [p for p in pins if p.partner is None and p.path != pin.path
                        and p.digest and p.text.endswith(target) and target.endswith(p.text)]
            name = f"`{target}` elsewhere"
        else:
            partners = [p for p in pins if p.path == target and p.partner is None]
            name = f"the pins of {target}"
        if not partners:
            problems.append(f"{pin.where()}: {pin.shape} `{pin.text}`: derived from {name}, which is no pin found")
        elif not all(reached.get(id(p)) for p in partners):
            problems.append(f"{pin.where()}: {pin.shape} `{pin.text}`: derived from {name}, which is not reached")
    return problems


def renovate_image(root: pathlib.Path) -> str:
    """The image renovate.yml runs: `renovate-version: <version>@sha256:<digest>`, appended to Renovate's repository."""
    text = read_text(root, RENOVATE_WORKFLOW) or ""
    versions = [strip_value(raw) for _, parent, key, raw in yaml_mappings(text) if parent == "with" and key == "renovate-version"]
    if len(versions) != 1 or not re.fullmatch(r"[0-9][0-9A-Za-z._-]*@sha256:" + HEX64, versions[0]):
        raise SystemExit(f"{RENOVATE_WORKFLOW}: expected one `renovate-version: <version>@sha256:<digest>`, found {versions}")
    return f"{RENOVATE_IMAGE_REPO}:{versions[0]}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--report", type=pathlib.Path, help="Renovate's report file (RENOVATE_REPORT_TYPE=file)")
    parser.add_argument("--root", type=pathlib.Path, default=ROOT, help="the tree to inventory (default: this one)")
    parser.add_argument("--renovate-image", action="store_true", help="print the Renovate image renovate.yml pins")
    parser.add_argument("--list", action="store_true", help="also print every pin found, with its shape")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    if args.renovate_image:
        print(renovate_image(root))
        return 0
    if args.report is None:
        parser.error("--report is required")
    extracted = read_report(args.report)
    exclude = []
    try:
        exclude.append(args.report.resolve().relative_to(root).as_posix())
    except ValueError:
        pass
    pins = inventory(root, exclude)
    problems = []
    if not extracted:
        problems.append(f"{args.report}: Renovate extracted no dependency at all — compared nothing")
    if not pins:
        problems.append(f"{root}: no pin found by any shape — compared nothing")
    problems += check(pins, extracted)
    if args.list:
        for pin in pins:
            print(f"pin {pin.where()} {pin.shape} {pin.text}", file=sys.stderr)
    if problems:
        for problem in problems:
            print(problem)
        print(f"pin_inventory: {len(problems)} problem(s) in {len(pins)} pins "
              "(ADR 0158 decision 1: every pin has an updater, and has a version)", file=sys.stderr)
        return 1
    files = len({p.path for p in pins})
    print(f"pin_inventory: {len(pins)} pins in {files} files, every one reached by Renovate")
    return 0


if __name__ == "__main__":
    sys.exit(main())
