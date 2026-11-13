#!/usr/bin/env python3
"""Offline self-test of `dependabot_follow.py`: each of the three follows on the shape it was written for, and on the
forgeries it must refuse or leave alone — a pin changed where Dependabot never writes, a registry that serves other
bytes than the digest it names, a metadata "write" that changes a committed checksum. No network, no git: the trees are
dicts, the registry is a fake. `checks.yml`'s `guards` job runs it.

    python3 scripts/dependabot_follow_selftest.py
"""
from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import dependabot_follow as df  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
failures: list[str] = []
checks = 0


def check(label: str, ok: bool, detail: str = "") -> None:
    global checks
    checks += 1
    if not ok:
        failures.append(f"{label}{': ' + detail if detail else ''}")


def refuses(label: str, fn, *args) -> None:
    try:
        result = fn(*args)
    except df.Refusal:
        check(label, True)
        return
    check(label, False, f"was not refused, returned {str(result)[:200]}")


def d(n: int) -> str:
    return "sha256:" + f"{n:064x}"


# --- images -----------------------------------------------------------------------------------------------------------

CONFIG = {"updates": [
    {"package-ecosystem": "docker", "directories": ["/src/tools"]},
    {"package-ecosystem": "docker-compose", "directories": ["/infra/docker", "/tests/e2e/stack"]},
    {"package-ecosystem": "npm", "directory": "/"},
]}
TRACKED = ["src/tools/Dockerfile", "src/tools/instance_backup.py", "infra/docker/docker-compose.yml",
           "infra/docker/docker-compose.prod.yml", "tests/e2e/stack/docker-compose.e2e.yml",
           "infra/kubernetes/charts/veyq/values.yaml", "src/tools/sub/Dockerfile", "infra/docker/notes.yml"]
REACHED = df.reachable_files(CONFIG, TRACKED)
check("reachable: Dockerfiles and compose files of the named directories",
      REACHED == {"src/tools/Dockerfile", "infra/docker/docker-compose.yml", "infra/docker/docker-compose.prod.yml",
                  "tests/e2e/stack/docker-compose.e2e.yml"}, str(sorted(REACHED)))
refuses("reachable: a config naming no image directory is refused", df.reachable_files, {"updates": []}, TRACKED)

OLD, NEW, FORGED = f"rclone/rclone:1.75.0@{d(1)}", f"rclone/rclone:1.75.2@{d(2)}", f"rclone/rclone:1.75.2@{d(666)}"
DB = f"mariadb:12.3@{d(3)}"


def tree(dockerfile_rclone: str, constant: str, chart_db: str = DB) -> dict[str, str]:
    return {
        "src/tools/Dockerfile": f"FROM {DB} AS mariadb-client\nFROM {dockerfile_rclone} AS rclone\n",
        "src/tools/instance_backup.py": f'RCLONE_IMAGE = "{constant}"\nDB_IMAGE = "{DB}"\n# see {OLD}\n',
        "infra/kubernetes/charts/veyq/values.yaml": f"database:\n  image: {chart_db}\n",
    }


base = tree(OLD, OLD)
head = tree(NEW, OLD)
out = df.follow_images(head, {"src/tools/Dockerfile": base["src/tools/Dockerfile"]}, REACHED)
check("images: the backup tool's constant follows the Dockerfile's bump",
      out == {"src/tools/instance_backup.py": f'RCLONE_IMAGE = "{NEW}"\nDB_IMAGE = "{DB}"\n# see {OLD}\n'}, str(out))
# (the comment line is no site: only the constant moves, and the mariadb constant beside it stays)

already = tree(NEW, NEW)
check("images: a branch this tool already followed is left alone",
      df.follow_images(already, {r: base[r] for r in ("src/tools/Dockerfile", "src/tools/instance_backup.py")},
                       REACHED) == {})
check("images: no bump, nothing written", df.follow_images(base, {}, REACHED) == {})

# Forgery 1: a pin moved only where Dependabot never writes.
forged_chart = tree(OLD, OLD, chart_db=f"mariadb:12.3@{d(666)}")
refuses("images FORGERY: a chart pin changed while no reached file moved",
        df.follow_images, forged_chart,
        {"infra/kubernetes/charts/veyq/values.yaml": base["infra/kubernetes/charts/veyq/values.yaml"]}, REACHED)
# Forgery 2: Dependabot's bump plus an unreached site set to another digest of the same tag.
refuses("images FORGERY: an unreached site moved to another digest than the bump's",
        df.follow_images, tree(NEW, FORGED),
        {r: base[r] for r in ("src/tools/Dockerfile", "src/tools/instance_backup.py")}, REACHED)
# Ambiguous: two reached files, two different new pins of one repository.
two = dict(head, **{"infra/docker/docker-compose.yml": f"services:\n  r:\n    image: {FORGED}\n"})
refuses("images: two different new pins of one repository are refused", df.follow_images, two,
        {"src/tools/Dockerfile": base["src/tools/Dockerfile"],
         "infra/docker/docker-compose.yml": f"services:\n  r:\n    image: {OLD}\n"}, REACHED)
# A reached site Dependabot left on the old pin.
left = dict(head, **{"infra/docker/docker-compose.yml": f"services:\n  r:\n    image: {OLD}\n"})
refuses("images: a reached site left on the old pin is refused", df.follow_images, left,
        {"src/tools/Dockerfile": base["src/tools/Dockerfile"]}, REACHED)

# The real tree: PR #23's shape (rclone moved in src/tools/Dockerfile) against today's files and today's config.
try:
    import yaml

    real_config = yaml.safe_load((ROOT / ".github/dependabot.yml").read_text())
    listed = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True, text=True)
    real_tracked = ([p for p in listed.stdout.split("\0") if p] if listed.returncode == 0 else
                    [p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts])
    real_reached = df.reachable_files(real_config, real_tracked)
    for rel in ("src/tools/Dockerfile", "infra/docker/docker-compose.yml", "tests/e2e/stack/docker-compose.e2e.yml"):
        check(f"real config: {rel} is a file Dependabot writes", rel in real_reached)
    for rel in ("src/tools/instance_backup.py", "infra/kubernetes/charts/veyq/values.yaml", "scripts/measure_volume.py"):
        check(f"real config: {rel} is no file Dependabot writes", rel not in real_reached)
    real_head = {rel: (ROOT / rel).read_text() for rel in ("src/tools/Dockerfile", "src/tools/instance_backup.py",
                                                           "src/web/webp-encoder/libwebp.pin")}
    current = next(s.ref for s in df.sites("src/tools/Dockerfile", real_head["src/tools/Dockerfile"])
                   if s.repo == "rclone/rclone")
    bumped_ref = f"rclone/rclone:9.9.9@{d(99)}"
    real_base = {"src/tools/Dockerfile": real_head["src/tools/Dockerfile"]}
    real_head["src/tools/Dockerfile"] = real_head["src/tools/Dockerfile"].replace(current, bumped_ref)
    real_out = df.follow_images(real_head, real_base, real_reached)
    check("real tree: an rclone bump in the tools Dockerfile moves RCLONE_IMAGE and nothing else",
          set(real_out) == {"src/tools/instance_backup.py"}
          and real_out["src/tools/instance_backup.py"]
          == real_head["src/tools/instance_backup.py"].replace(current, bumped_ref), str(sorted(real_out)))
except ModuleNotFoundError:
    failures.append("PyYAML is required for the real-config cases (pip install pyyaml)")


# --- Playwright -------------------------------------------------------------------------------------------------------

def index_body(*arches: str) -> bytes:
    return json.dumps({"schemaVersion": 2, "mediaType": df.INDEX_TYPES[0], "manifests": [
        {"digest": d(i + 10), "platform": {"os": "linux", "architecture": a}} for i, a in enumerate(arches)]}).encode()


GOOD = index_body("amd64", "arm64")
GOOD_DIGEST = "sha256:" + hashlib.sha256(GOOD).hexdigest()


def registry(body: bytes = GOOD, digest: str | None = None, content_type: str = df.INDEX_TYPES[0], status: int = 200):
    calls: list[str] = []

    def fetch(url: str, accept: str):
        calls.append(url)
        named = digest if digest is not None else "sha256:" + hashlib.sha256(body).hexdigest()
        return status, {"content-type": content_type, "docker-content-digest": named}, body
    return fetch, calls


OLD_PW = f"{df.PLAYWRIGHT_REPO}:v1.64.1-noble@{d(7)}"


def pw_tree(spec: str, image: str = OLD_PW) -> dict[str, str]:
    return {
        "tests/e2e/package.json": json.dumps({"devDependencies": {"@playwright/test": spec}}),
        "tests/e2e/stack/docker-compose.e2e.yml": f"services:\n  a:\n    image: {image}\n  b:\n    image: {image}\n",
    }


pw_base = {"tests/e2e/package.json": pw_tree("1.64.1")["tests/e2e/package.json"]}
fetch, calls = registry()
out = df.follow_playwright(pw_tree("1.65.0"), pw_base, fetch)
new_pw = f"{df.PLAYWRIGHT_REPO}:v1.65.0-noble@{GOOD_DIGEST}"
check("playwright: both image lines move to the library's version, suffix kept, MCR's index digest",
      out == {"tests/e2e/stack/docker-compose.e2e.yml": f"services:\n  a:\n    image: {new_pw}\n  b:\n    image: {new_pw}\n"},
      str(out))
check("playwright: the registry was asked for exactly the new tag",
      calls == ["https://mcr.microsoft.com/v2/playwright/manifests/v1.65.0-noble"], str(calls))

fetch, calls = registry()
check("playwright: no library move, nothing written and no registry call",
      df.follow_playwright(pw_tree("1.64.1"), {}, fetch) == {} and calls == [])
refuses("playwright FORGERY: a body that is not the digest it was served under",
        df.follow_playwright, pw_tree("1.65.0"), pw_base, registry(digest=d(666))[0])
refuses("playwright FORGERY: an index without arm64", df.follow_playwright, pw_tree("1.65.0"), pw_base,
        registry(body=index_body("amd64"))[0])
refuses("playwright: a single-platform manifest is no index", df.follow_playwright, pw_tree("1.65.0"), pw_base,
        registry(content_type="application/vnd.oci.image.manifest.v1+json")[0])
refuses("playwright: an unpublished tag (404)", df.follow_playwright, pw_tree("1.65.0"), pw_base,
        registry(status=404, body=b"")[0])
refuses("playwright: a range instead of an exact version", df.follow_playwright, pw_tree("^1.65.0"), pw_base,
        registry()[0])
refuses("playwright: a version that would inject into the tag", df.follow_playwright, pw_tree("1.65.0/../x"), pw_base,
        registry()[0])
followed_base = dict(pw_base, **{"tests/e2e/stack/docker-compose.e2e.yml": pw_tree("1.64.1")[
    "tests/e2e/stack/docker-compose.e2e.yml"]})
check("playwright: a branch this tool already moved, still what MCR serves, is left alone",
      df.follow_playwright(pw_tree("1.65.0", new_pw), followed_base, registry()[0]) == {})
refuses("playwright FORGERY: the branch names the new tag under a digest MCR does not serve",
        df.follow_playwright, pw_tree("1.65.0", f"{df.PLAYWRIGHT_REPO}:v1.65.0-noble@{d(666)}"), followed_base,
        registry()[0])


# --- Gradle verification metadata -------------------------------------------------------------------------------------

HEADER = ('<?xml version="1.0" encoding="UTF-8"?>\n<verification-metadata xmlns="https://schema.gradle.org/dependency-'
          'verification">\n   <configuration>\n      <verify-metadata>true</verify-metadata>\n   </configuration>\n'
          '   <components>\n')
FOOTER = "   </components>\n</verification-metadata>\n"


def component(group: str, name: str, version: str, sha: str = "a" * 64) -> str:
    return (f'      <component group="{group}" name="{name}" version="{version}">\n'
            f'         <artifact name="{name}-{version}.jar">\n'
            f'            <sha256 value="{sha}" origin="Generated by Gradle"/>\n'
            f'         </artifact>\n      </component>\n')


def metadata(*components: str, header: str = HEADER) -> str:
    return header + "".join(components) + FOOTER


committed = metadata(component("a", "lib", "1.0"), component("c", "kept", "2.0"), component("x", "dropped", "1.0"))
written = metadata(component("a", "lib", "1.0"), component("a", "lib", "1.1", "b" * 64), component("c", "kept", "2.0"),
                   component("e", "new", "1.0", "c" * 64), component("x", "dropped", "1.0"))
cold = ["a:lib:1.1", "c:kept:2.0", "e:new:1.0"]
accepted = df.accept_gradle(committed, written, cold)
check("gradle: the replaced version leaves, everything else stays",
      accepted == metadata(component("a", "lib", "1.1", "b" * 64), component("c", "kept", "2.0"),
                           component("e", "new", "1.0", "c" * 64), component("x", "dropped", "1.0")), accepted)
check("gradle: a write that added nothing changes nothing", df.accept_gradle(committed, committed, cold) == committed)
check("gradle: a replaced version the cold home still fetches stays",
      "1.0" in df.accept_gradle(committed, written, cold + ["a:lib:1.0"]).split('name="lib" version="')[1][:3])
refuses("gradle FORGERY: a committed artifact's checksum changed",
        df.accept_gradle, committed, written.replace(component("c", "kept", "2.0"), component("c", "kept", "2.0", "f" * 64)),
        cold)
also_trusted = component("c", "kept", "2.0").replace(
    f'<sha256 value="{"a" * 64}" origin="Generated by Gradle"/>',
    f'<sha256 value="{"a" * 64}" origin="Generated by Gradle"><also-trust value="{"f" * 64}"/></sha256>')
check("gradle: the also-trust forgery below is built", also_trusted != component("c", "kept", "2.0"))
refuses("gradle FORGERY: an also-trust slipped under a committed checksum",
        df.accept_gradle, committed, written.replace(component("c", "kept", "2.0"), also_trusted), cold)
refuses("gradle FORGERY: the trust rules changed",
        df.accept_gradle, committed, written.replace("<verify-metadata>true", "<verify-metadata>false"), cold)
refuses("gradle FORGERY: a committed component vanished",
        df.accept_gradle, committed, written.replace(component("c", "kept", "2.0"), ""), cold)
refuses("gradle FORGERY: a DOCTYPE (entity expansion) is never parsed",
        df.accept_gradle, committed, written.replace("<verification-metadata ", "<!DOCTYPE x [<!ENTITY a 'b'>]>\n<verification-metadata ", 1), cold)
refuses("gradle FORGERY: a malformed checksum on a new component",
        df.accept_gradle, committed, written.replace("c" * 64, "not-hex"), cold)
refuses("gradle: a cold-cache listing that is no coordinate list", df.accept_gradle, committed, written, ["a:lib"])
check("gradle: only build inputs under src/backend/ need the metadata",
      df.gradle_needed(["src/backend/gradle/libs.versions.toml"]) and df.gradle_needed(["src/backend/core/build.gradle.kts"])
      and not df.gradle_needed(["src/web/package.json", "pnpm-lock.yaml"]))


# --- the gate ---------------------------------------------------------------------------------------------------------

REPO, SHA = "owner/veyq", "f" * 40


def api(user: str = df.DEPENDABOT, ref: str = "dependabot/gradle/src/backend/gradle-minor-patch-1a2b", sha: str = SHA,
        authors: tuple = (df.DEPENDABOT,), head_repo: str = REPO, state: str = "open"):
    def call(path: str):
        if path.endswith("/commits"):
            return [{"author": {"login": a}} for a in authors]
        return {"state": state, "user": {"login": user},
                "head": {"ref": ref, "sha": sha, "repo": {"full_name": head_repo}},
                "base": {"ref": "main", "repo": {"full_name": REPO}}}
    return call


check("gate: Dependabot's open pull request is followed",
      df.gate(REPO, 7, SHA, api()) == {"follow": "true", "branch": "dependabot/gradle/src/backend/gradle-minor-patch-1a2b",
                                       "base_ref": "main"})
check("gate: a branch this workflow already pushed to is still followed",
      df.gate(REPO, 7, SHA, api(authors=(df.DEPENDABOT, df.FOLLOW_BOT)))["follow"] == "true")
check("gate: a branch a person pushed to is left alone",
      df.gate(REPO, 7, SHA, api(authors=(df.DEPENDABOT, "someone")))["follow"] == "false")
check("gate: a branch that moved since the derive run is left alone", df.gate(REPO, 7, SHA, api(sha="e" * 40))["follow"] == "false")
refuses("gate FORGERY: a pull request by anyone but Dependabot", df.gate, REPO, 7, SHA, api(user="someone"))
refuses("gate FORGERY: a head in another repository", df.gate, REPO, 7, SHA, api(head_repo="fork/veyq"))
refuses("gate: a head branch that is not Dependabot's", df.gate, REPO, 7, SHA, api(ref="main"))


# --- end to end: the command line on a real git tree ------------------------------------------------------------------

def run_git(cwd: pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", "-C", str(cwd), "-c", "user.name=t", "-c", "user.email=t@t", *args],
                          capture_output=True, text=True, check=True).stdout.strip()


with tempfile.TemporaryDirectory() as scratch:
    work = pathlib.Path(scratch) / "tree"
    work.mkdir()
    (work / ".github").mkdir()
    (work / ".github/dependabot.yml").write_text(json.dumps(CONFIG))  # JSON is YAML
    for rel, text in base.items():
        (work / rel).parent.mkdir(parents=True, exist_ok=True)
        (work / rel).write_text(text)
    run_git(work, "init", "--quiet")
    run_git(work, "add", "-A")
    run_git(work, "commit", "--quiet", "-m", "base")
    base_sha = run_git(work, "rev-parse", "HEAD")
    (work / "src/tools/Dockerfile").write_text(head["src/tools/Dockerfile"])
    run_git(work, "commit", "--quiet", "-am", "bump")
    message = pathlib.Path(scratch) / "message.txt"
    cli = [sys.executable, str(pathlib.Path(__file__).resolve().parent / "dependabot_follow.py")]
    ran = subprocess.run(cli + ["follow", "--tree", str(work), "--config", str(work / ".github/dependabot.yml"),
                                "--base", base_sha, "--message", str(message)], capture_output=True, text=True)
    check("end to end: the follow exits 0", ran.returncode == 0, ran.stderr)
    check("end to end: the constant is rewritten in the tree",
          f'RCLONE_IMAGE = "{NEW}"' in (work / "src/tools/instance_backup.py").read_text())
    check("end to end: the only change is that file",
          run_git(work, "status", "--porcelain") == "M src/tools/instance_backup.py", run_git(work, "status", "--porcelain"))
    check("end to end: a commit message names it", message.is_file() and "instance_backup.py" in message.read_text())
    needed = subprocess.run(cli + ["gradle-needed", "--tree", str(work), "--base", base_sha], capture_output=True, text=True)
    check("end to end: an image bump needs no Gradle derive", needed.stdout.strip() == "needed=false", needed.stdout)
    run_git(work, "checkout", "--quiet", "--", ".")
    (work / "infra/kubernetes/charts/veyq/values.yaml").write_text(forged_chart["infra/kubernetes/charts/veyq/values.yaml"])
    run_git(work, "commit", "--quiet", "-am", "forged")
    ran = subprocess.run(cli + ["follow", "--tree", str(work), "--config", str(work / ".github/dependabot.yml"),
                                "--base", base_sha, "--message", str(message)], capture_output=True, text=True)
    check("end to end FORGERY: a chart pin no reached file explains exits 1 and writes nothing",
          ran.returncode == 1 and run_git(work, "status", "--porcelain") == "", ran.stderr)


if failures:
    print(f"DEPENDABOT FOLLOW SELF-TEST FAILED ({len(failures)} of {checks}):")
    for f in failures:
        print("  -", f)
    sys.exit(1)
print(f"dependabot follow self-test OK: {checks} checks")
