#!/usr/bin/env python3
"""The hand-follows a Dependabot pull request needs, done deterministically and without a language model.

Run by `.github/workflows/dependabot-follow-derive.yml` (read-only) and `.github/workflows/dependabot-follow.yml`
(the one job that writes, and only to the Dependabot branch); `.github/dependabot.yml`'s header says how the two fit.
Every pull request Dependabot opened on 2026-11-12 needed the same mechanical follow by hand. These are the three:

  1. **images** — an image pin Dependabot does not reach. One repository is pinned under one tag and one `repo:tag`
     under one digest (`validate_image_pins.py`), but Dependabot writes only the compose files and Dockerfiles its
     `docker` and `docker-compose` entries name; the chart's values, the backup tool's `*_IMAGE` constants and
     `measure_volume.py`'s `PROBE_IMAGE` stay behind (PR #20/#23: rclone; PR #15: mailpit). The bump is read from the
     files Dependabot reaches (derived from `.github/dependabot.yml`, never listed here) and written to every other
     image site of the same repository — the three site shapes `validate_image_pins.py` reads, and nothing else.
  2. **playwright** — `@playwright/test` moved (the npm `playwright` group); the image `mcr.microsoft.com/playwright`
     must run the same version (`validate_image_pins.py`'s last check; PR #19/#22). The new tag keeps the old suffix
     (`-noble`), its multi-arch INDEX digest is read from MCR's registry API — anonymous, no token — and is taken only
     when the body hashes to the `Docker-Content-Digest` the registry named, is an image index, and carries
     linux/amd64 and linux/arm64.
  3. **gradle** — a Gradle bump regenerates `src/backend/gradle/verification-metadata.xml` by the cold-home recipe of
     `src/backend/CLAUDE.md` (PR #16). That run executes the bumped build, so it happens in the read-only workflow;
     what it wrote comes here as an artifact, as DATA, and is accepted only when it is an append: the
     `<configuration>` unchanged, every component already committed still there with exactly its committed
     checksums. Then only the versions the bump replaced, and that the cold Gradle home no longer holds, are pruned.

**What it never reads.** Not the pull request's title or body (Dependabot copies upstream release notes into it —
third-party text), no changelog, no commit message. The gate reads the pull request's author, head and base refs and
SHAs, and its commits' author logins; everything else is the tree's files and the registry's bytes.

**What makes it refuse** (exit 1, nothing written), each a case in `dependabot_follow_selftest.py`: an image pin in a
file Dependabot does not reach that changed to anything but what a reached file now says (a forged pin, or a hand
edit nobody can tell from one); two different new pins for one repository, or a reached site of a bumped repository
left on another pin; a Playwright version that is not one exact `X.Y.Z`; a registry answer that is no index, lacks an
architecture, or whose body is not the digest it was served under; a Playwright pin this tool already wrote that MCR
no longer serves under that tag; a written metadata file with a DOCTYPE, another `<configuration>`, a committed
component missing, or a committed checksum changed. A pull request whose branch carries a commit by anyone but
Dependabot or this workflow is left alone (`gate` answers `follow=false`): a person has taken it over.

**Its limits — what a green run does NOT prove.** It trusts what Dependabot wrote at the sites it reaches — exactly
the trust the pull request already asks the reviewer for — and does not check that an image digest from Docker Hub
or GHCR is a multi-arch index (only MCR's is resolved here). A new Gradle component's checksum is trusted on first
write, as in the hand recipe. The recipe's `quarkusDev` pass after a Quarkus bump is not run (it needs a database
and a store); a dependency the bump drops without replacing it is not pruned (no new version of it was written, so
nothing tells it apart from one only `quarkusDev` resolves); a Gradle wrapper bump's `scripts/gradle_wrapper_jar.sha256`
is not followed. The checks a person approves after the push are what prove the result.

    python3 scripts/dependabot_follow.py gate --repo OWNER/NAME --pr N --head-sha SHA
    python3 scripts/dependabot_follow.py gradle-needed --tree DIR --base SHA
    python3 scripts/dependabot_follow.py gradle-listing --gradle-home DIR
    python3 scripts/dependabot_follow.py follow --tree DIR --config DEPENDABOT_YML --base SHA --derived DIR --message FILE
"""
from __future__ import annotations  # `str | None` below — the repo's scripts run on Python 3.9

import argparse
import dataclasses
import hashlib
import json
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from typing import Callable, Iterable

# The same lines validate_image_pins.py draws: what a site is, and what a pinned reference looks like.
SKIP_PREFIXES = ("changes/", "docs/", "site/src/content/")
PINNED = re.compile(
    r"^(?P<repo>[a-z0-9][a-z0-9._/-]*(?::[0-9]+)?(?:/[a-z0-9._-]+)*):(?P<tag>[A-Za-z0-9._-]+)@(?P<digest>sha256:[0-9a-f]{64})$"
)
YAML_SITE = re.compile(r"""^(\s*(?:-\s+)?image:\s*["']?)([^\s"'#]+)""")
FROM_SITE = re.compile(r"^(\s*FROM\s+(?:--\S+\s+)*)(\S+)", re.IGNORECASE)
COPY_SITE = re.compile(r"^(\s*COPY\s+.*--from=)(\S+)", re.IGNORECASE)
PY_SITE = re.compile(r"""^([A-Z][A-Z0-9_]*IMAGE\s*=\s*["'])([^"']+)""")
DOCKERFILE_NAME = re.compile(r"(?:.+\.)?(?:Dockerfile|Containerfile)(?:\..+)?")
# dependabot-core's docker-compose fetcher (FILENAME_REGEX, read on main 2e9740c, 2026-11-12, dependabot.yml says so).
COMPOSE_NAME = re.compile(r"(?:docker-)?compose(?:-\w+)?(?:\.\w+)?\.ya?ml")

PLAYWRIGHT_REPO = "mcr.microsoft.com/playwright"
PLAYWRIGHT_PACKAGE = "@playwright/test"
PLAYWRIGHT_TAG = re.compile(r"^v(?P<version>[0-9]+\.[0-9]+\.[0-9]+)(?P<suffix>(?:-[A-Za-z0-9._-]+)?)$")
EXACT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
MCR_MANIFEST = "https://mcr.microsoft.com/v2/playwright/manifests/{tag}"
INDEX_TYPES = ("application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json")
NEEDED_PLATFORMS = {("linux", "amd64"), ("linux", "arm64")}
MAX_MANIFEST_BYTES = 1 << 20

METADATA = "src/backend/gradle/verification-metadata.xml"
GRADLE_INPUT = re.compile(r"^src/backend/(?:.+\.gradle\.kts|.+\.versions\.toml|gradle/wrapper/gradle-wrapper\.properties)$")
NS = "{https://schema.gradle.org/dependency-verification}"
COORDINATE = re.compile(r"^[^:\s]+:[^:\s]+:[^:\s]+$")
MAX_METADATA_BYTES = 32 << 20

DEPENDABOT = "dependabot[bot]"
FOLLOW_BOT = "github-actions[bot]"
BRANCH = re.compile(r"^dependabot/[A-Za-z0-9._/+-]+$")
BASE_BRANCH = re.compile(r"^(?:main|release/[A-Za-z0-9._-]+)$")


class Refusal(Exception):
    """The follow cannot be done mechanically; a person decides. Nothing is written."""


# --- image sites -----------------------------------------------------------------------------------------------------


@dataclasses.dataclass(frozen=True)
class Site:
    line: int  # 0-based
    start: int
    end: int
    repo: str
    tag: str
    digest: str

    @property
    def ref(self) -> str:
        return f"{self.repo}:{self.tag}@{self.digest}"


def site_kind(rel: str) -> str | None:
    if rel.startswith(SKIP_PREFIXES) or "node_modules/" in rel:
        return None
    name = rel.rsplit("/", 1)[-1]
    if name.endswith((".yml", ".yaml")):
        return "yaml"
    if DOCKERFILE_NAME.fullmatch(name):
        return "dockerfile"
    if name.endswith(".py"):
        return "python"
    return None


def sites(rel: str, text: str | None) -> list[Site]:
    """Every pinned reference at a site `validate_image_pins.py` reads — by line, so it can be rewritten in place."""
    kind = site_kind(rel)
    if kind is None or text is None:
        return []
    patterns = {"yaml": (YAML_SITE,), "dockerfile": (FROM_SITE, COPY_SITE), "python": (PY_SITE,)}[kind]
    found = []
    for number, line in enumerate(text.splitlines()):
        for pattern in patterns:
            match = pattern.match(line)
            if not match:
                continue
            pinned = PINNED.match(match.group(2))
            if pinned:
                found.append(Site(number, match.start(2), match.end(2), pinned.group("repo"), pinned.group("tag"),
                                  pinned.group("digest")))
            break
    return found


def rewrite(text: str, replacements: dict[int, list[tuple[int, int, str]]]) -> str:
    lines = text.splitlines(keepends=True)
    for number, spans in replacements.items():
        line = lines[number]
        for start, end, new in sorted(spans, reverse=True):
            line = line[:start] + new + line[end:]
        lines[number] = line
    return "".join(lines)


def reachable_files(config: dict, tracked: Iterable[str]) -> set[str]:
    """The files Dependabot writes image pins into: its `docker` and `docker-compose` entries' directories, read the way
    its fetchers read them (one directory each, not recursive). Derived from the config, never listed."""
    reached: set[str] = set()
    tracked = list(tracked)
    for entry in config.get("updates") or []:
        ecosystem = entry.get("package-ecosystem")
        if ecosystem not in ("docker", "docker-compose"):
            continue
        names = DOCKERFILE_NAME if ecosystem == "docker" else COMPOSE_NAME
        directories = list(entry.get("directories") or []) + ([entry["directory"]] if entry.get("directory") else [])
        for directory in directories:
            prefix = str(directory).strip("/")
            for rel in tracked:
                parent, _, name = rel.rpartition("/")
                if parent == prefix and names.fullmatch(name):
                    reached.add(rel)
    if not reached:
        raise Refusal("the Dependabot config names no docker or docker-compose directory with a file in it — "
                      "which pins Dependabot wrote cannot be told from the ones it did not")
    return reached


def follow_images(head: dict[str, str], base_of: dict[str, str | None], reached: set[str]) -> dict[str, str]:
    """`head`: every image-site file of the pull request's tree. `base_of`: the base's text of each file the pull
    request changed (None when it added it). Returns the files to write."""
    def refs(rel: str, text: str | None) -> set[str]:
        return {s.ref for s in sites(rel, text)}

    def repo_of(ref: str) -> str:
        return PINNED.match(ref).group("repo")

    # The bump: what Dependabot wrote at the sites it reaches.
    written: dict[str, set[str]] = {}
    for rel in sorted(base_of):
        if rel in reached and rel in head:
            for ref in refs(rel, head[rel]) - refs(rel, base_of[rel]):
                written.setdefault(repo_of(ref), set()).add(ref)
    bump: dict[str, str] = {}
    for repo, new in sorted(written.items()):
        if len(new) != 1:
            raise Refusal(f"`{repo}` was moved to {len(new)} different pins ({', '.join(sorted(new))}) at the sites "
                          f"Dependabot reaches — which one the rest should follow is a person's call")
        bump[repo] = next(iter(new))
    for rel in sorted(reached & head.keys()):
        for site in sites(rel, head[rel]):
            if site.repo in bump and site.ref != bump[site.repo]:
                raise Refusal(f"{rel}:{site.line + 1} still pins `{site.ref}` although Dependabot moved `{site.repo}` "
                              f"to `{bump[site.repo]}` elsewhere — a site it reaches and did not move is not this "
                              f"tool's to move")

    # A forgery, or a hand edit: a pin changed where Dependabot never writes, to anything but the bump.
    for rel in sorted(base_of):
        if rel in reached or rel not in head:
            continue
        for ref in sorted(refs(rel, head[rel]) - refs(rel, base_of[rel])):
            if bump.get(repo_of(ref)) != ref:
                raise Refusal(f"{rel} pins `{ref}`, which this pull request changed in a file Dependabot does not "
                              f"write, and no file it does write says so — not followed, a person reads this one")

    out: dict[str, str] = {}
    for rel in sorted(head.keys() - reached):
        replacements: dict[int, list[tuple[int, int, str]]] = {}
        for site in sites(rel, head[rel]):
            if site.repo in bump and site.ref != bump[site.repo]:
                replacements.setdefault(site.line, []).append((site.start, site.end, bump[site.repo]))
        if replacements:
            out[rel] = rewrite(head[rel], replacements)
    return out


# --- Playwright -------------------------------------------------------------------------------------------------------

Fetch = Callable[[str, str], "tuple[int, dict[str, str], bytes]"]


def http_fetch(url: str, accept: str) -> tuple[int, dict[str, str], bytes]:
    request = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "veyq-dependabot-follow"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 — a fixed https URL
            return response.status, {k.lower(): v for k, v in response.headers.items()}, response.read(MAX_MANIFEST_BYTES + 1)
    except urllib.error.HTTPError as exc:
        return exc.code, {k.lower(): v for k, v in exc.headers.items()}, b""


def resolve_index(tag: str, fetch: Fetch) -> str:
    """MCR's multi-arch index digest for `playwright:<tag>`, checked against the bytes it was served with."""
    if not PLAYWRIGHT_TAG.match(tag):
        raise Refusal(f"`{tag}` is no Playwright tag this tool resolves")
    status, headers, body = fetch(MCR_MANIFEST.format(tag=tag), ", ".join(INDEX_TYPES))
    if status != 200:
        raise Refusal(f"MCR answered {status} for {PLAYWRIGHT_REPO}:{tag} — the tag may not be published yet")
    if len(body) > MAX_MANIFEST_BYTES:
        raise Refusal(f"MCR's manifest for {tag} is larger than {MAX_MANIFEST_BYTES} bytes — not an index")
    content_type = headers.get("content-type", "").split(";")[0].strip()
    if content_type not in INDEX_TYPES:
        raise Refusal(f"MCR served {PLAYWRIGHT_REPO}:{tag} as `{content_type}`, not an image index — a single-platform "
                      f"manifest would pin one architecture")
    named = headers.get("docker-content-digest", "")
    actual = "sha256:" + hashlib.sha256(body).hexdigest()
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", named) or named != actual:
        raise Refusal(f"MCR named `{named}` for {PLAYWRIGHT_REPO}:{tag}, but the body it served hashes to `{actual}` — "
                      f"nothing is pinned to a digest its bytes do not have")
    try:
        index = json.loads(body)
    except ValueError as exc:
        raise Refusal(f"MCR's index for {tag} is not JSON ({exc})") from None
    platforms = {((m.get("platform") or {}).get("os"), (m.get("platform") or {}).get("architecture"))
                 for m in index.get("manifests") or [] if isinstance(m, dict)}
    missing = NEEDED_PLATFORMS - platforms
    if missing:
        raise Refusal(f"MCR's index for {PLAYWRIGHT_REPO}:{tag} carries no {', '.join('/'.join(p) for p in sorted(missing))} "
                      f"image — lane L1 runs on both")
    return actual


def playwright_specs(files: dict[str, str | None]) -> dict[str, str]:
    specs: dict[str, str] = {}
    for rel, text in sorted(files.items()):
        if rel.rsplit("/", 1)[-1] != "package.json" or "node_modules/" in rel or text is None:
            continue
        try:
            manifest = json.loads(text)
        except ValueError as exc:
            raise Refusal(f"{rel} does not parse as JSON ({exc})") from None
        for section in ("dependencies", "devDependencies", "optionalDependencies"):
            spec = (manifest.get(section) or {}).get(PLAYWRIGHT_PACKAGE)
            if spec is not None:
                specs[f"{rel} ({section})"] = str(spec)
    return specs


def follow_playwright(head: dict[str, str], base_of: dict[str, str | None], fetch: Fetch) -> dict[str, str]:
    """`head`: the tree's package.json files and image-site files. Returns the files to write."""
    head_specs = playwright_specs(head)
    base_specs = playwright_specs({rel: base_of.get(rel, head[rel]) for rel in head})
    if head_specs == base_specs:
        return {}  # this pull request did not move the library
    versions = set(head_specs.values())
    if len(versions) != 1 or not EXACT_VERSION.match(next(iter(versions))):
        raise Refusal(f"{PLAYWRIGHT_PACKAGE} is not one exact version across the tree ({head_specs}) — the image "
                      f"follows one `X.Y.Z`")
    version = next(iter(versions))

    found = [(rel, s) for rel in sorted(head) for s in sites(rel, head[rel]) if s.repo == PLAYWRIGHT_REPO]
    if not found:
        raise Refusal(f"no tracked image site pins `{PLAYWRIGHT_REPO}` — nothing to move to {version}")
    suffixes = set()
    for rel, site in found:
        match = PLAYWRIGHT_TAG.match(site.tag)
        if not match:
            raise Refusal(f"{rel}:{site.line + 1}: `{site.tag}` does not read as `vX.Y.Z[-<suffix>]`")
        suffixes.add(match.group("suffix"))
    if len(suffixes) != 1:
        raise Refusal(f"the Playwright image is pinned with {len(suffixes)} suffixes ({sorted(suffixes)}) — which "
                      f"base image the new version takes is a person's call")
    tag = f"v{version}{suffixes.pop()}"

    if all(site.tag == tag for _, site in found):
        changed = [(rel, s) for rel, s in found
                   if rel in base_of and s.ref not in {b.ref for b in sites(rel, base_of[rel])}]
        if not changed:
            return {}
        # This branch already moved the image (a run of this tool). Its digest must still be what MCR serves.
        digest = resolve_index(tag, fetch)
        for rel, site in changed:
            if site.digest != digest:
                raise Refusal(f"{rel}:{site.line + 1} pins `{site.ref}`, but MCR serves {tag} as `{digest}` — a pin "
                              f"this tool did not resolve, or a tag re-pushed since; resolve it by hand")
        return {}

    new_ref = f"{PLAYWRIGHT_REPO}:{tag}@{resolve_index(tag, fetch)}"
    out: dict[str, str] = {}
    for rel in sorted({rel for rel, _ in found}):
        replacements: dict[int, list[tuple[int, int, str]]] = {}
        for site in sites(rel, head[rel]):
            if site.repo == PLAYWRIGHT_REPO and site.ref != new_ref:
                replacements.setdefault(site.line, []).append((site.start, site.end, new_ref))
        if replacements:
            out[rel] = rewrite(head[rel], replacements)
    return out


# --- Gradle verification metadata -------------------------------------------------------------------------------------


def parse_metadata(text: str, what: str) -> tuple[bytes, dict[tuple[str, str, str], dict[str, frozenset]]]:
    if len(text) > MAX_METADATA_BYTES:
        raise Refusal(f"{what} is larger than {MAX_METADATA_BYTES} bytes")
    if re.search(r"<!(?:DOCTYPE|ENTITY)", text, re.IGNORECASE):
        raise Refusal(f"{what} declares a DOCTYPE or an entity — Gradle writes neither")
    try:
        root = ET.fromstring(text)
    except ET.ParseError as exc:
        raise Refusal(f"{what} does not parse as XML ({exc})") from None
    if root.tag != f"{NS}verification-metadata":
        raise Refusal(f"{what} is no Gradle verification metadata (root `{root.tag}`)")
    configuration = root.find(f"{NS}configuration")
    canonical = ET.tostring(configuration) if configuration is not None else b""
    components: dict[tuple[str, str, str], dict[str, frozenset]] = {}
    for component in root.iterfind(f"{NS}components/{NS}component"):
        key = (component.get("group", ""), component.get("name", ""), component.get("version", ""))
        if key in components:
            raise Refusal(f"{what} lists {':'.join(key)} twice")
        artifacts = {}
        for artifact in component.iterfind(f"{NS}artifact"):
            sums = []
            for child in artifact.iter():
                if child is artifact or child.tag.endswith("also-trust"):
                    continue
                value = child.get("value", "")
                sums.append((child.tag.replace(NS, ""), value))
                for trusted in child.iterfind(f"{NS}also-trust"):
                    sums.append((child.tag.replace(NS, "") + "+also-trust", trusted.get("value", "")))
            artifacts[artifact.get("name", "")] = frozenset(sums)
        components[key] = artifacts
    return canonical, components


def accept_gradle(committed: str, written: str, cold: Iterable[str]) -> str:
    """The metadata to commit: `written` (what the cold-home run wrote over `committed`) when it is an append, with the
    versions the bump replaced pruned where the cold Gradle home no longer holds them."""
    cold = set(cold)
    for entry in cold:
        if not COORDINATE.match(entry):
            raise Refusal(f"the cold-cache listing carries `{entry[:80]}`, which is no group:name:version")
    base_configuration, base = parse_metadata(committed, "the committed metadata")
    new_configuration, new = parse_metadata(written, "the written metadata")
    if new_configuration != base_configuration:
        raise Refusal("the written metadata's <configuration> differs from the committed one — a write adds "
                      "components, it never changes the trust rules")
    for key, artifacts in sorted(base.items()):
        if key not in new:
            raise Refusal(f"{':'.join(key)} is committed but missing from the written metadata — Gradle's write only "
                          f"adds, so this file was not written by it")
        for name, sums in sorted(artifacts.items()):
            if new[key].get(name) != sums:
                raise Refusal(f"{':'.join(key)}'s `{name}` is committed with other checksums than the written ones — an "
                              f"artifact already pinned now hashes differently; that is never followed mechanically")
    for key, artifacts in sorted(new.items()):
        for name, sums in sorted(artifacts.items()):
            if not sums or any(not re.fullmatch(r"[0-9a-f]{32,128}", value) for _, value in sums):
                raise Refusal(f"{':'.join(key)}'s `{name}` carries no well-formed checksum")

    added = new.keys() - base.keys()
    replaced = {(g, n) for g, n, _ in added}
    prune = sorted(key for key in new.keys() - added
                   if key[:2] in replaced and ":".join(key) not in cold)
    out = written
    for group, name, version in prune:
        block = re.compile(
            r'^[ \t]*<component group="' + re.escape(group) + r'" name="' + re.escape(name) + r'" version="'
            + re.escape(version) + r'">\n(?:.*\n)*?[ \t]*</component>\n', re.MULTILINE)
        out, count = block.subn("", out, count=2)
        if count != 1:
            raise Refusal(f"{group}:{name}:{version} is to be pruned but appears {count} times in Gradle's layout")
    return out


def cold_listing(gradle_home: pathlib.Path) -> list[str]:
    """Every group:name:version the cold Gradle home downloaded (`caches/modules-2/files-2.1/<g>/<n>/<v>/…`)."""
    root = gradle_home / "caches" / "modules-2" / "files-2.1"
    if not root.is_dir():
        raise Refusal(f"{root} does not exist — the cold-home run downloaded nothing, or Gradle moved its cache")
    return sorted(f"{g.name}:{n.name}:{v.name}" for g in root.iterdir() if g.is_dir()
                  for n in g.iterdir() if n.is_dir() for v in n.iterdir() if v.is_dir())


# --- the tree ---------------------------------------------------------------------------------------------------------


def git(tree: pathlib.Path, *args: str) -> str:
    out = subprocess.run(["git", "-C", str(tree), *args], capture_output=True, text=True)
    if out.returncode != 0:
        raise Refusal(f"`git {' '.join(args)}` failed: {out.stderr.strip()}")
    return out.stdout


def changed_files(tree: pathlib.Path, base: str) -> list[str]:
    return [p for p in git(tree, "diff", "--name-only", "-z", base, "HEAD").split("\0") if p]


def base_text(tree: pathlib.Path, base: str, rel: str) -> str | None:
    out = subprocess.run(["git", "-C", str(tree), "show", f"{base}:{rel}"], capture_output=True, text=True)
    return out.stdout if out.returncode == 0 else None


def gradle_needed(changed: Iterable[str]) -> bool:
    return any(GRADLE_INPUT.match(rel) for rel in changed)


def follow(tree: pathlib.Path, config_path: pathlib.Path, base: str, derived: pathlib.Path | None,
           fetch: Fetch = http_fetch) -> tuple[dict[str, str], list[str]]:
    try:
        import yaml
    except ModuleNotFoundError:
        raise Refusal("PyYAML is required (pip install --require-hashes --only-binary :all: "
                      "-r scripts/requirements-release.txt)") from None
    config = yaml.safe_load(config_path.read_text()) or {}
    tracked = [p for p in git(tree, "ls-files", "-z").split("\0") if p]
    changed = changed_files(tree, base)
    base_of = {rel: base_text(tree, base, rel) for rel in changed}
    relevant = [rel for rel in tracked if site_kind(rel) or rel.rsplit("/", 1)[-1] == "package.json"]
    head = {rel: (tree / rel).read_text() for rel in relevant if (tree / rel).is_file() and not (tree / rel).is_symlink()}
    out: dict[str, str] = {}
    notes: list[str] = []

    images = follow_images(head, base_of, reachable_files(config, tracked))
    for rel, text in images.items():
        notes.append(f"image pins followed in {rel}")
    out.update(images)

    playwright = follow_playwright({**head, **out}, base_of, fetch)
    for rel in playwright:
        notes.append(f"Playwright image moved to {PLAYWRIGHT_PACKAGE}'s version in {rel}")
    out.update(playwright)

    if gradle_needed(changed):
        if derived is None:
            raise Refusal("this pull request moves a Gradle build input, and no derived metadata came with it")
        files = {p.name for p in derived.iterdir()} if derived.is_dir() else set()
        if not {"verification-metadata.xml", "cold-cache.json"} <= files:
            raise Refusal(f"this pull request moves a Gradle build input, and the read-only run's artifact lacks the "
                          f"written metadata or the cold-cache listing ({sorted(files)})")
        listing = json.loads((derived / "cold-cache.json").read_text())
        if not isinstance(listing, list) or not all(isinstance(e, str) for e in listing):
            raise Refusal("the cold-cache listing is not a list of strings")
        committed = (tree / METADATA).read_text()
        accepted = accept_gradle(committed, (derived / "verification-metadata.xml").read_text(), listing)
        if accepted != committed:
            out[METADATA] = accepted
            notes.append(f"{METADATA} regenerated from a cold Gradle home and pruned")
    return out, notes


# --- the gate ---------------------------------------------------------------------------------------------------------


def gh_api(path: str) -> object:
    out = subprocess.run(["gh", "api", "--paginate", "--slurp", path], capture_output=True, text=True)
    if out.returncode != 0:
        raise Refusal(f"`gh api {path}` failed: {out.stderr.strip()}")
    pages = json.loads(out.stdout)
    if pages and all(isinstance(p, list) for p in pages):
        return [item for page in pages for item in page]
    return pages[0] if len(pages) == 1 else pages


def gate(repo: str, number: int, head_sha: str, api: Callable[[str], object] = gh_api) -> dict[str, str]:
    """Whether this run may follow pull request `number`, read from GitHub (never from the run's own inputs), and the
    branch and base it would push to and diff against. Raises on a pull request that is not Dependabot's."""
    pr = api(f"repos/{repo}/pulls/{number}")
    if not isinstance(pr, dict):
        raise Refusal("the pull request could not be read")
    login = (pr.get("user") or {}).get("login")
    head, base = pr.get("head") or {}, pr.get("base") or {}
    if login != DEPENDABOT:
        raise Refusal(f"pull request {number} was opened by `{login}`, not {DEPENDABOT}")
    if (head.get("repo") or {}).get("full_name") != repo or (base.get("repo") or {}).get("full_name") != repo:
        raise Refusal(f"pull request {number}'s head or base is not in {repo}")
    if head.get("sha") != head_sha:
        return {"follow": "false", "reason": "the branch moved since the run that derived it"}
    branch, base_ref = str(head.get("ref", "")), str(base.get("ref", ""))
    if not BRANCH.match(branch) or ".." in branch:
        raise Refusal(f"`{branch[:120]}` is not a Dependabot branch name")
    if not BASE_BRANCH.match(base_ref):
        raise Refusal(f"`{base_ref[:120]}` is not a branch this tool follows into")
    if pr.get("state") != "open":
        return {"follow": "false", "reason": "the pull request is not open"}
    commits = api(f"repos/{repo}/pulls/{number}/commits")
    authors = {((c.get("author") or {}).get("login")) for c in commits} if isinstance(commits, list) else {None}
    if not commits or authors - {DEPENDABOT, FOLLOW_BOT}:
        return {"follow": "false", "reason": f"the branch carries commits by {sorted(map(str, authors))} — a person has "
                                             f"taken it over"}
    return {"follow": "true", "branch": branch, "base_ref": base_ref}


# --- command line -----------------------------------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("gate")
    p.add_argument("--repo", required=True)
    p.add_argument("--pr", required=True, type=int)
    p.add_argument("--head-sha", required=True)
    p = sub.add_parser("gradle-needed")
    p.add_argument("--tree", required=True, type=pathlib.Path)
    p.add_argument("--base", required=True)
    p = sub.add_parser("gradle-listing")
    p.add_argument("--gradle-home", required=True, type=pathlib.Path)
    p = sub.add_parser("follow")
    p.add_argument("--tree", required=True, type=pathlib.Path)
    p.add_argument("--config", required=True, type=pathlib.Path)
    p.add_argument("--base", required=True)
    p.add_argument("--derived", type=pathlib.Path)
    p.add_argument("--message", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "gate":
            for key, value in gate(args.repo, args.pr, args.head_sha).items():
                print(f"{key}={value}")
        elif args.command == "gradle-needed":
            print(f"needed={'true' if gradle_needed(changed_files(args.tree, args.base)) else 'false'}")
        elif args.command == "gradle-listing":
            print(json.dumps(cold_listing(args.gradle_home), indent=0))
        else:
            out, notes = follow(args.tree, args.config, args.base, args.derived)
            for rel, text in sorted(out.items()):
                (args.tree / rel).write_text(text)
            if notes:
                args.message.write_text("chore(deps): follow the bump where Dependabot does not write\n\n"
                                        "Written by scripts/dependabot_follow.py (.github/workflows/dependabot-follow.yml):\n"
                                        + "".join(f"- {n}\n" for n in notes))
            print("\n".join(notes) if notes else "nothing to follow")
    except Refusal as exc:
        print(f"::error::not followed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
