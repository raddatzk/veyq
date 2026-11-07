#!/usr/bin/env python3
"""Pin the upgrade proof's pure parts, without Docker, a jar or a second worktree (ADR 0057 Decision 7, cr-upgrade-proof).

`scripts/run_web_e2e.py --upgrade` boots two builds - the predecessor's and the candidate's - and the parts of it that
decide WHICH predecessor and WHAT a red run means are plain functions. This asserts each of them:
  1. the request: `--upgrade` asks for the default predecessor, `--upgrade-from <ref>` / `=<ref>` for that one, and
     anything else is a test path as before; `--upgrade-from` with no ref, or both flags, is refused;
  2. the default predecessor: the first parent of `HEAD` while the pin file says `release: none`, and once a release
     is set the newest `v<major>.<minor>` tag the caller found - by version order, where string order disagrees
     (`v1.10` after `v1.9`); a set release with no such tag is refused, never guessed;
  3. the pin file read both ways: the `release:` line, the highest migration, and the migrations that differ between
     two builds (a changed checksum, a removed file, matched by file name so a moved one is neither) - the cause a red
     proof names before the first release;
  4. the boot log read for Flyway's own refusal - a checksum mismatch, an applied migration the candidate no longer
     has, one below the restored history - and nothing named for a log without one;
  5. which suite of a tree is the seeder (the `upgrade` group's first member), and none for a tree without it;
  6. the record both builds share: `src/web/src/test/upgradeRecord.fixture.json`'s record is accepted, so is every edit
     it lists as accepted (a cover named by its uploaded blob, the shape the seeder writes now), and every edit it lists
     as refused is refused with exactly one problem - the same fixture `src/web/src/test/upgradeRecord.test.ts` holds the
     TypeScript reader to, so the two validators cannot drift apart unseen (they had: the orchestrator still required
     `memory.cover.mediaId` after the seeder moved to `blobId`, collection run 2026-10-23, F10);
  7. the predecessor's surfaces in either layout (ADR 0106 decision 7): its web client in a fabricated tree of each,
     and its seeder found in the commit before the code surfaces moved under `src/` as well as in HEAD;
  8. the major entry (ADR 0085 decision 10) - the release a release of major N is proved from a second time and whose
     tools image bakes it: the newest `v<N-1>.<minor>` tag by version order, none for major 0 or without such a tag;
     the second predecessor is that entry only where it is not the default one; and on a fabricated history with a
     `release/1.x` branch, the fix release tagged there - which `HEAD^1` does not reach - is the entry
     (`--upgrade-major-entry` asks for it), while the default stays the newest tag `HEAD^1` reaches; and the tools
     image's stamp (`release_assets.py --major-entry`) is that same derivation, never a second one.

What it cannot show: that the proof runs - the worktree, the two builds, the restore and the two suites need Docker
and a jar, and the run itself is the proof (its summary is recorded in the criterion's comment). Not a
`validate_*.py`: it asserts nothing about the repository, only about the orchestrator, and it runs beside
`instance_backup_selftest.py` in `checks.yml`'s `web-e2e` job. Runnable locally:

    python3 scripts/upgrade_proof_selftest.py
"""
from __future__ import annotations  # the repo's scripts run on Python 3.9

import copy
import json
import os
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402 - where the code surfaces live (ADR 0106)
import run_web_e2e as e2e  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
FIXTURE = repo_layout.WEB / "src" / "test" / "upgradeRecord.fixture.json"

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail and not ok else ''}")
    if not ok:
        failures.append(label)


def refused(fn, *args) -> str:
    """The message `fn(*args)` refuses with, or '' when it did not refuse."""
    try:
        fn(*args)
    except ValueError as e:
        return str(e)
    return ""


def request_cases() -> None:
    R = e2e.upgrade_request
    check("no flag: every argument is a test path", R(["a.e2e.test.ts"]) == (False, None, ["a.e2e.test.ts"]))
    check("--upgrade: the default predecessor", R(["--upgrade"]) == (True, None, []))
    check("--upgrade-from <ref>", R(["--upgrade-from", "abc123"]) == (True, "abc123", []))
    check("--upgrade-from=<ref>", R(["--upgrade-from=v1.2"]) == (True, "v1.2", []))
    check("test paths stay beside the flag", R(["x.e2e.test.ts", "--upgrade"]) == (True, None, ["x.e2e.test.ts"]))
    check("--upgrade-from without a ref is refused", "names the predecessor" in refused(R, ["--upgrade-from"]))
    check("--upgrade-from= without a ref is refused", "names the predecessor" in refused(R, ["--upgrade-from="]))
    check("both flags are refused", "one predecessor" in refused(R, ["--upgrade", "--upgrade-from", "x"]))
    check("--upgrade-major-entry: the major entry", R(["--upgrade-major-entry"]) == (True, e2e.MAJOR_ENTRY_REQUEST, []))
    check("--upgrade-major-entry beside --upgrade is refused",
          "one predecessor" in refused(R, ["--upgrade", "--upgrade-major-entry"]))
    check("--upgrade-from cannot smuggle the major-entry request in as a ref",
          "names the predecessor" in refused(R, ["--upgrade-from", e2e.MAJOR_ENTRY_REQUEST]))


def resolve_cases() -> None:
    """`--resolve`, the question `checks.yml`'s `upgrade-proof` legs ask before they build anything: the predecessor as the
    last line `predecessor=<ref>`, empty where the major entry has no second proof, and nothing started — `docker` is
    never looked for. Run against stand-ins for the two derivations."""
    import contextlib
    import io

    def resolved(argv: list, entry: str | None, default: str = "v2.3") -> tuple:
        real = e2e.major_entry_proof_ref, e2e.default_predecessor_ref, e2e.shutil.which
        looked: list = []
        e2e.major_entry_proof_ref = lambda root=None: (entry, "stand-in")
        e2e.default_predecessor_ref = lambda root=None: (default, "stand-in")
        e2e.shutil.which = lambda name: (looked.append(name), None)[1]
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                try:
                    rc = e2e.main(argv)
                except SystemExit as e:
                    rc = f"exit {e}"
        finally:
            e2e.major_entry_proof_ref, e2e.default_predecessor_ref, e2e.shutil.which = real
        lines = out.getvalue().splitlines()
        return rc, lines[-1] if lines else "", looked

    check("--resolve: the major entry's predecessor, nothing started",
          resolved(["--upgrade-major-entry", "--resolve"], "v1.10") == (0, "predecessor=v1.10", []))
    check("--resolve: no second proof, an empty predecessor, nothing started",
          resolved(["--upgrade-major-entry", "--resolve"], None) == (0, "predecessor=", []))
    check("--resolve: the default predecessor", resolved(["--upgrade", "--resolve"], None) == (0, "predecessor=v2.3", []))
    check("--resolve: a named one", resolved(["--resolve", "--upgrade-from", "abc"], None) == (0, "predecessor=abc", []))
    rc, _, looked = resolved(["--resolve"], None)
    check("--resolve without an upgrade flag is refused before anything", str(rc).startswith("exit") and not looked,
          str(rc))


def predecessor_cases() -> None:
    D = e2e.default_predecessor
    check("release: none -> the first parent of HEAD", D("none", ["v1.0", "v1.1"]) == "HEAD^1")
    check("a set release -> the newest release tag", D("202610041201", ["v1.0", "v1.2", "v1.1"]) == "v1.2")
    check("version order, not string order", D("202610041201", ["v1.9", "v1.10", "v0.99"]) == "v1.10")
    check("a major outranks every minor", D("202610041201", ["v1.99", "v2.0"]) == "v2.0")
    check("only v<major>.<minor> tags count", D("202610041201", ["v1.3-rc", "v1.2", "release-9", "v1.4.1"]) == "v1.2")
    message = refused(D, "202610041201", ["nightly", "v1"])
    check("a set release with no release tag is refused, naming the line", "release: 202610041201" in message, message)


def major_entry_cases() -> None:
    E, P = e2e.major_entry, e2e.second_predecessor
    tags = ["v0.4", "v1.0", "v1.9", "v1.10", "v2.0", "v2.3", "v1.11-rc", "v1.12.1", "nightly"]
    check("major entry: the newest release of the previous major", E(2, tags) == "v1.10")
    check("major entry: by version order, not string order", E(2, ["v1.10", "v1.9"]) == "v1.10")
    check("major entry: only the previous major's tags, never the current one's or an older one's",
          E(3, tags) == "v2.3" and E(1, tags) == "v0.4")
    check("major entry: only v<major>.<minor> tags count", E(2, ["v1.11-rc", "v1.12.1", "v1.3"]) == "v1.3")
    check("major entry: none for major 0", E(0, tags) is None)
    check("major entry: none where the previous major has no release tag", E(4, tags) is None and E(2, []) is None)
    check("second predecessor: the entry where it is not the default", P("v2.3", "v1.10") == "v1.10")
    check("second predecessor: none where the entry IS the default (a major's first release)", P("v1.9", "v1.9") is None)
    check("second predecessor: none without an entry", P("v2.3", None) is None and P("HEAD^1", None) is None)
    history_cases()
    stamp_cases()


def stamp_cases() -> None:
    """The tools image's stamp is the SAME derivation: `release_assets.major_entry` asks [major_entry_ref] for the
    release's major and only reshapes the tag, so the gate cannot admit an entry the proof did not run from. Its
    `run_web_e2e` is this module object (one `sys.modules` entry), so a stand-in here is the one it calls."""
    import release_assets  # noqa: E402 - PyYAML, which run_web_e2e already needs, is all it adds
    real, asked = e2e.major_entry_ref, []
    try:
        e2e.major_entry_ref = lambda major=None, root=None: (asked.append(major), "v1.10")[1]
        stamped = release_assets.major_entry("2.3")
        asked_for_stamp = list(asked)
        with tempfile.TemporaryDirectory(prefix="major-entry-manifest-") as out:
            dest = release_assets.produce(pathlib.Path(out), "veyq:local", "veyq-admin:local", False, release="2.3")
            manifest = json.loads((dest / release_assets.MANIFEST).read_text())
        e2e.major_entry_ref = lambda major=None, root=None: None
        none = release_assets.major_entry("2.0")
    finally:
        e2e.major_entry_ref = real
    check("stamp: the tools image bakes run_web_e2e's major entry for the release's major, as `<major>.<minor>`",
          stamped == "1.10" and asked_for_stamp == [2], f"{stamped!r}, asked for {asked_for_stamp}")
    check("stamp: no entry, no stamp", none is None, repr(none))
    check("stamp: the release's manifest names the same entry as `majorEntry`, which veyqctl routes by",
          manifest.get("majorEntry") == "1.10", repr(manifest.get("majorEntry")))


def history_cases() -> None:
    """The impure half on a fabricated history: `main` released 1.0 and 1.1, bumped to major 2 and released 2.0, while
    `release/1.x` (cut from v1.1) released the fix 1.2 - a tag `main` never reaches. Each step is the shape ADR 0085
    decision 10 orders; the derivation must read every tag for the entry and only the reachable ones for the default."""
    env = {**os.environ, "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"}

    def git(root: pathlib.Path, *args: str) -> None:
        subprocess.run(["git", "-c", "user.name=selftest", "-c", "user.email=selftest@invalid",
                        "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args],
                       cwd=root, env=env, check=True, capture_output=True)

    def commit(root: pathlib.Path, message: str, version: str | None = None, release: str = "202610041201") -> None:
        if version is not None:
            (root / "VERSION").write_text(f"{version}\n")
        pins = root / e2e.PIN_FILE
        pins.parent.mkdir(parents=True, exist_ok=True)
        pins.write_text(f"release: {release}\n")
        (root / "notes").write_text(message)
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)

    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        git(root, "init", "-q", "-b", "main")
        commit(root, "1.0", version="1")
        git(root, "tag", "v1.0")
        commit(root, "1.1")
        git(root, "tag", "v1.1")
        commit(root, "the bump", version="2")           # major 2's first release, 2.0, is this commit
        check("history: major 2's first release, no fix release yet - entry = default = v1.1, proved once",
              e2e.default_predecessor_ref(root)[0] == "v1.1" and e2e.major_entry_ref(root=root) == "v1.1"
              and e2e.major_entry_proof_ref(root)[0] is None)
        git(root, "tag", "v2.0")
        git(root, "branch", "release/1.x", "v1.1")
        git(root, "checkout", "-q", "release/1.x")
        commit(root, "the fix 1.2", version="1")
        git(root, "tag", "v1.2")
        git(root, "checkout", "-q", "main")
        commit(root, "2.1")                              # HEAD^1 is v2.0; v1.2 lies on the branch only
        default, entry = e2e.default_predecessor_ref(root)[0], e2e.major_entry_ref(root=root)
        check("history: the default predecessor is the newest tag HEAD^1 reaches (v2.0)", default == "v2.0", default)
        check("history: the major entry is the branch's fix release v1.2, which HEAD^1 does not reach",
              entry == "v1.2", str(entry))
        second, why = e2e.major_entry_proof_ref(root)
        check("history: the release after a fix release is proved a second time, from v1.2", second == "v1.2", why)
        check("history: an explicit major (1, a release of the branch) enters from no tag of major 0",
              e2e.major_entry_ref(1, root) is None)
        commit(root, "pre-release", release="none")
        check("history: while the pin file says `release: none` there is no entry, whatever is tagged",
              e2e.major_entry_ref(root=root) is None and e2e.major_entry_proof_ref(root)[0] is None)


PINS_BEFORE = """# comment
release: none
aaaa  backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql
bbbb  backend/apps/memories/src/main/resources/db/migration/V202611070832__memories_baseline.sql
cccc  backend/core/src/main/resources/db/migration/V202611101200__core_gone.sql
"""
PINS_AFTER = """release: 202611121200
aaaa  backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql
b2b2  backend/apps/memories/src/main/resources/db/migration/V202611070832__memories_baseline.sql
dddd  backend/core/src/main/resources/db/migration/V202611121200__core_new.sql
"""


def pin_cases() -> None:
    before, after = e2e.pinned_migrations(PINS_BEFORE), e2e.pinned_migrations(PINS_AFTER)
    check("the release line: none", e2e.read_release(PINS_BEFORE) == "none")
    check("the release line: a version", e2e.read_release(PINS_AFTER) == "202611121200")
    check("a pin file without a release line is refused", "release:" in refused(e2e.read_release, "aaaa  x.sql\n"))
    check("pins are read by path", len(before) == 3 and before[
        "backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql"] == "aaaa")
    check("the highest migration, before", e2e.highest_migration(before) == "202611101200")
    check("the highest migration, after", e2e.highest_migration(after) == "202611121200")
    check("no migration, no highest", e2e.highest_migration({}) is None)
    changed = e2e.changed_migrations(before, after)
    check("a changed checksum and a removed file are named, an added one is not", changed == [
        "changed: backend/apps/memories/src/main/resources/db/migration/V202611070832__memories_baseline.sql",
        "removed: backend/core/src/main/resources/db/migration/V202611101200__core_gone.sql",
    ], repr(changed))
    check("identical pins name nothing", e2e.changed_migrations(before, before) == [])
    # ADR 0106 stage 2 moved the backend under `src/`: the pin file's paths change prefix, Flyway's record does not.
    moved = {f"src/{path}": sha for path, sha in before.items()}
    check("a migration that only moved is neither changed nor removed", e2e.changed_migrations(before, moved) == [],
          repr(e2e.changed_migrations(before, moved)))
    moved_and_edited = dict(moved, **{
        "src/backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql": "a2a2"})
    check("a moved migration whose checksum changed is named by its new path",
          e2e.changed_migrations(before, moved_and_edited) == [
              "changed: src/backend/core/src/main/resources/db/migration/V202611070831__core_baseline.sql"],
          repr(e2e.changed_migrations(before, moved_and_edited)))


def flyway_cases() -> None:
    F = e2e.flyway_cause
    mismatch = ("ERROR [io.qua.run.Application] Failed to start application\n"
                "org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have failed "
                "validation\nMigration checksum mismatch for migration version 202611070831\n-> Applied to database "
                ": 123\n-> Resolved locally    : 456.")
    cause = F(mismatch) or ""
    check("a checksum mismatch is named, with its version", "checksum" in cause and "V202611070831" in cause, cause)
    cause = F("Validate failed: Migrations have failed validation\nDetected applied migration not resolved locally: "
              "202611101200.") or ""
    check("an applied migration the candidate lacks is named", "V202611101200" in cause and "no longer" in cause, cause)
    cause = F("Validate failed: Migrations have failed validation\nDetected resolved migration not applied to "
              "database: 202611051200.") or ""
    check("a migration below the restored history is named", "V202611051200" in cause and "below" in cause, cause)
    cause = F("FlywayValidateException: Validate failed: something new") or ""
    check("any other validation failure is still named as Flyway's", "Flyway" in cause, cause)
    check("a clean boot names nothing",
          F("Successfully applied 2 migrations to schema `veyq_e2e_1`, now at version v202611070832") is None)


def seeder_cases() -> None:
    S = e2e.upgrade_seeder
    tree = ["src/features/memories/media.e2e.test.ts", "src/features/instanceBackup/upgrade.b-read.e2e.test.ts",
            "src/features/instanceBackup/upgrade.a-seed.e2e.test.ts", "src/features/x/upgrades.e2e.test.ts"]
    check("the seeder is the upgrade group's first member", S(tree) == "src/features/instanceBackup/upgrade.a-seed.e2e.test.ts")
    check("a tree without the group has no seeder", S(["src/features/memories/media.e2e.test.ts"]) is None)


def layout_cases() -> None:
    """The predecessor's surfaces are found in either layout (ADR 0106 decision 7): a worktree of a commit from before
    stage 2 carries them at the root. Both on fabricated trees and against this repository's own history."""
    with tempfile.TemporaryDirectory() as d:
        for layout, path in (("this layout", repo_layout.rel(repo_layout.WEB)),
                             ("the layout before stage 2", repo_layout.LEGACY_SURFACES[repo_layout.WEB])):
            tree = pathlib.Path(d) / layout.replace(" ", "-")
            (tree / path).mkdir(parents=True)
            found = e2e.Build("predecessor", tree, tree / "x.jar").web
            check(f"a predecessor tree in {layout} has its web client found", found == tree / path, str(found))
    # The commit before stage 2's move: HEAD while it still carries the legacy layout, else the first parent of the
    # commit that took the web client's manifest away from its legacy place. checks.yml's web-e2e job checks out the
    # whole history (fetch-depth: 0), so it is always reachable there.
    legacy_manifest = f"{repo_layout.LEGACY_SURFACES[repo_layout.WEB]}/package.json"
    on_head = subprocess.run(["git", "cat-file", "-e", f"HEAD:{legacy_manifest}"], cwd=ROOT, capture_output=True)
    if on_head.returncode == 0:
        legacy = "HEAD"
    else:
        moved = subprocess.run(["git", "log", "-1", "--format=%H", "--diff-filter=D", "HEAD", "--", legacy_manifest],
                               cwd=ROOT, capture_output=True, text=True).stdout.strip()
        legacy = f"{moved}^1" if moved else ""
    check("the commit before the code surfaces moved is found in this repository's history", bool(legacy),
          f"no commit carries {legacy_manifest}")
    for label, commit in (("the commit before the move", legacy), ("HEAD", "HEAD")):
        if commit:
            seeder = e2e.upgrade_seeder(e2e.predecessor_web_sources(commit))
            check(f"{label} ({commit}) has its seeder found", seeder is not None and seeder.endswith(".e2e.test.ts"),
                  str(seeder))


def edited(record: dict, edit: dict) -> dict:
    out = copy.deepcopy(record)
    at = out
    for step in edit["path"][:-1]:
        at = at[step]
    last = edit["path"][-1]
    if edit.get("delete"):
        del at[last]
    else:
        at[last] = edit["value"]
    return out


def record_cases() -> None:
    fixture = json.loads(FIXTURE.read_text())
    problems = e2e.upgrade_record_problems(fixture["record"])
    check("the fixture record is accepted", problems == [], "; ".join(problems))
    for edit in fixture["accepted"]:
        problems = e2e.upgrade_record_problems(edited(fixture["record"], edit))
        check(f"accepted: {edit['why']}", problems == [], "; ".join(problems))
    for edit in fixture["refused"]:
        problems = e2e.upgrade_record_problems(edited(fixture["record"], edit))
        check(f"refused with exactly one problem: {edit['why']}", len(problems) == 1, "; ".join(problems) or "accepted")
    check("no object at all is refused", e2e.upgrade_record_problems([]) == ["the record is not a JSON object"])


def main() -> int:
    request_cases()
    predecessor_cases()
    major_entry_cases()
    resolve_cases()
    pin_cases()
    flyway_cases()
    seeder_cases()
    layout_cases()
    record_cases()
    if failures:
        print(f"[selftest] {len(failures)} failure(s)", file=sys.stderr)
        return 1
    print("[selftest] the upgrade proof picks its predecessor and its major entry, reads both builds' migrations, "
          "names Flyway's refusal and checks the shared record the way the TypeScript reader does")
    return 0


if __name__ == "__main__":
    sys.exit(main())
