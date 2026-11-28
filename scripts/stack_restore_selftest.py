#!/usr/bin/env python3
"""Self-test of lane L3's pure parts (ADR 0091 decision 6, amended 2026-11-26),
offline and Docker-free: which archived backups the lane restores, how a shard takes its part, and what it refuses of an
archive before anything is installed.

  1. **the selection** (`run_web_e2e.backup_selection`, beside `major_entry`): the eighteen releases of 2026-11-26 with
     the highest migration of each one's own pin file (the CR's measured substrate) select exactly v0.2480 (the major
     entry), v1.0, v1.168, v1.248 and v1.260 — one per schema state, the oldest of each, by version order (v1.10 after
     v1.9); a fix release of the previous major on its `release/<major>.x` branch moves the entry; a release whose pin
     file names no migration is refused;
  2. **the selection from git** (`backup_selection_ref`), on a fabricated history: each tag's OWN pin file decides its
     state (a later edit on `main` changes no released state), every tag counts — a fix release tagged on
     `release/1.x`, which `main` never reaches, is major 2's entry — and while the pin file says `release: none` the
     selection is empty;
  3. **the shards** (`run_stack_restore.shard_of`): the selection dealt round-robin in version order over the matrix's
     shards — disjoint, together the whole selection, a shard beyond the selection empty — and a shard the matrix does
     not name refused; a shard over `MAX_PER_SHARD` is red naming the fix;
  4. **an archive's facts** (`check_facts`): another release's archive, another schema state than the pin file's, a
     record version the reader does not read, a domain the lane's stack does not serve — each refused, naming itself;
  5. **the archive, read** (`fetch`, with the registry and cosign stood in): a selected backup that is not archived is
     red naming the release, never skipped; a registry that will not say is red; a signature that does not verify under
     `archive.yml`'s identity is refused before anything is pulled, and the pull is by the digest that verified;
  6. **one backup's red is its own** (`blocking`): a red of a backup's stage skips that backup's later stages and no
     other backup's; a red of a shared stage skips them all.

What it cannot see: the stack — the install, the restore and the two suites are the lane's run, recorded in the CR.
`checks.yml`'s `guards` job runs it:

    python3 scripts/stack_restore_selftest.py
"""
from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import archive_backup  # noqa: E402
import run_stack_restore as l3  # noqa: E402
import run_web_e2e as e2e  # noqa: E402
from stack_lane import Red  # noqa: E402

failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {label}{(' — ' + detail) if detail and not ok else ''}")
    if not ok:
        failures.append(label)


def refusal(fn, *args) -> str:
    """The message `fn(*args)` refuses with (ValueError or Red), or '' when it did not refuse."""
    try:
        fn(*args)
    except (ValueError, Red) as error:
        return str(error)
    return ""


# ---- 1. the selection -------------------------------------------------------------------------------------------

# The 2026-11-26 grouping (the CR's substrate table): every release tag and the highest migration of its own pin file.
STATE_A, STATE_B, STATE_C, STATE_D = "202611070833", "202611122155", "202611160756", "202611221402"
FIXTURE = {
    "v0.2480": STATE_A, "v1.0": STATE_A, "v1.61": STATE_A, "v1.66": STATE_A, "v1.82": STATE_A, "v1.84": STATE_A,
    "v1.133": STATE_A, "v1.152": STATE_A, "v1.168": STATE_B, "v1.215": STATE_B, "v1.223": STATE_B, "v1.243": STATE_B,
    "v1.247": STATE_B, "v1.248": STATE_C, "v1.249": STATE_C, "v1.260": STATE_D, "v1.312": STATE_D, "v1.318": STATE_D,
}


def selected(major: int, states: dict[str, str | None], tags: list[str] | None = None) -> list[tuple[str, str]]:
    return e2e.backup_selection(major, list(states) if tags is None else tags, states.get)


def selection_cases() -> None:
    got = selected(1, FIXTURE)
    check("selection: 2026-11-26's eighteen releases select v0.2480, v1.0, v1.168, v1.248, v1.260",
          got == [("v0.2480", STATE_A), ("v1.0", STATE_A), ("v1.168", STATE_B), ("v1.248", STATE_C),
                  ("v1.260", STATE_D)], str(got))
    shuffled = sorted(FIXTURE, key=lambda t: t[::-1])
    check("selection: the order the tags arrive in changes nothing", selected(1, FIXTURE, shuffled) == got)
    check("selection: v0.2480 shares v1.0's state and is kept — the entry is the previous major's, never grouped in",
          ("v0.2480", STATE_A) in got and ("v1.0", STATE_A) in got)
    # A fix release of major 0, on release/0.x: the entry moves to it, and nothing else of the selection does.
    fixed = {**FIXTURE, "v0.2481": STATE_A}
    check("selection: a fix release of the previous major moves the entry (v0.2481 for v0.2480)",
          [t for t, _ in selected(1, fixed)] == ["v0.2481", "v1.0", "v1.168", "v1.248", "v1.260"],
          str(selected(1, fixed)))
    check("selection: by version order, not string order — v1.9 is older than v1.10",
          [t for t, _ in selected(1, {"v1.10": STATE_A, "v1.9": STATE_A, "v1.100": STATE_B})] == ["v1.9", "v1.100"])
    check("selection: a release that opens a new state joins it",
          [t for t, _ in selected(1, {**FIXTURE, "v1.330": "202611301200"})][-1] == "v1.330")
    check("selection: major 2's selection is its own releases and major 1's newest (the entry), nothing of major 0",
          [t for t, _ in selected(2, {**FIXTURE, "v2.0": "202612010000", "v2.4": "202612010000"})] == ["v1.318", "v2.0"])
    check("selection: only v<major>.<minor> tags count", [t for t, _ in selected(1, {
        "v1.0": STATE_A, "v1.2-rc": STATE_B, "v1.3.1": STATE_C, "nightly": STATE_D})] == ["v1.0"])
    check("selection: no release of the major and no entry selects nothing", selected(0, {}) == [])
    why = refusal(e2e.backup_selection, 1, ["v1.0", "v1.4"], {"v1.0": STATE_A, "v1.4": None}.get)
    check("selection: a release whose pin file names no migration is refused, naming it", "v1.4" in why, why)


def history_cases() -> None:
    """The impure half on a fabricated history, each tag's pin file its own: `main` released 1.0 (state A) and 1.1 (B),
    a later edit on main changed nothing released, then major 2 released 2.0; `release/1.x` from v1.1 released 1.2."""
    env = {**os.environ, "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1"}

    def git(root: pathlib.Path, *args: str) -> None:
        subprocess.run(["git", "-c", "user.name=selftest", "-c", "user.email=selftest@invalid", "-c",
                        "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args], cwd=root, env=env, check=True,
                       capture_output=True)

    def commit(root: pathlib.Path, message: str, migrations: list[str], version: str | None = None,
               release: str = "202610041201") -> None:
        if version is not None:
            (root / "VERSION").write_text(f"{version}\n")
        pins = root / e2e.PIN_FILE
        pins.parent.mkdir(parents=True, exist_ok=True)
        pins.write_text(f"release: {release}\n" + "".join(
            f"{'a' * 64}  src/backend/core/src/main/resources/db/migration/V{m}__m.sql\n" for m in migrations))
        (root / "notes").write_text(message)
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", message)

    with tempfile.TemporaryDirectory(prefix="l3-selection-") as d:
        root = pathlib.Path(d)
        git(root, "init", "-q", "-b", "main")
        commit(root, "1.0", ["202610041201"], version="1")
        git(root, "tag", "v1.0")
        commit(root, "1.1", ["202610041201", "202610101200"])
        git(root, "tag", "v1.1")
        commit(root, "an edit after 1.1", ["202610041201", "202610101200", "202610201200"])
        got = [t for t, _ in e2e.backup_selection_ref(root)]
        check("history: each tag's own pin file decides its state (v1.0 and v1.1 are two states)",
              got == ["v1.0", "v1.1"], str(got))
        commit(root, "the bump", ["202610041201", "202610101200", "202610201200"], version="2")
        git(root, "tag", "v2.0")
        git(root, "branch", "release/1.x", "v1.1")
        git(root, "checkout", "-q", "release/1.x")
        commit(root, "the fix 1.2", ["202610041201", "202610101200"], version="1")
        git(root, "tag", "v1.2")
        git(root, "checkout", "-q", "main")
        commit(root, "2.1", ["202610041201", "202610101200", "202610201200"])
        got = [t for t, _ in e2e.backup_selection_ref(root)]
        check("history: the fix release v1.2 on release/1.x, which main never reaches, is major 2's entry",
              got == ["v1.2", "v2.0"], str(got))
        git(root, "checkout", "-q", "--detach", "HEAD")
        git(root, "rm", "-q", e2e.PIN_FILE)
        git(root, "commit", "-q", "-m", "no pins")
        git(root, "tag", "v2.3")
        git(root, "checkout", "-q", "main")
        why = refusal(e2e.backup_selection_ref, root)
        check("history: a tag without a pin file is refused, naming it", "v2.3" in why, why)
        commit(root, "pre-release", [], release="none")
        check("history: while the pin file says `release: none` the selection is empty, whatever is tagged",
              e2e.backup_selection_ref(root) == [])


# ---- 3. the shards ----------------------------------------------------------------------------------------------

def shard_cases() -> None:
    everything = [l3.Backup(t, h) for t, h in selected(1, FIXTURE)]
    shards = ["1", "2", "3"]
    parts = {s: l3.shard_of(everything, s, shards) for s in shards}
    check("shards: dealt round-robin in version order",
          {s: [b.tag for b in p] for s, p in parts.items()}
          == {"1": ["v0.2480", "v1.248"], "2": ["v1.0", "v1.260"], "3": ["v1.168"]},
          str({s: [b.tag for b in p] for s, p in parts.items()}))
    union = [b for p in parts.values() for b in p]
    check("shards: disjoint, and together the whole selection",
          len(union) == len(set(union)) == len(everything) and set(union) == set(everything))
    check("shards: a shard beyond the selection restores nothing",
          l3.shard_of(everything[:2], "3", shards) == [])
    check("shards: a shard checks.yml does not name is refused", "is not one of" in refusal(
        l3.shard_of, everything, "4", shards))
    check("shards: numbers in the matrix's order, not string order (2 before 10)",
          sorted(["1", "10", "2"], key=lambda s: (len(s), s)) == ["1", "2", "10"])
    over = [l3.Backup(f"v1.{i}", str(i)) for i in range(l3.MAX_PER_SHARD + 1)]
    why = refusal(l3.check_capacity, over, "1")
    check("shards: one over MAX_PER_SHARD is red, naming the matrix to widen", "add a shard" in why, why)
    check("shards: MAX_PER_SHARD exactly passes", refusal(l3.check_capacity, over[:-1], "1") == "")


# ---- 4. an archive's facts ----------------------------------------------------------------------------------------

def facts_cases() -> None:
    backup = l3.Backup("v1.260", STATE_D)
    good = {"tag": "v1.260", "highestMigration": f"V{STATE_D}", "recordVersion": 1, "domain": "veyq.test"}
    check("facts: the archive the selection expects is read, its domain installed on",
          l3.check_facts(good, backup) == "veyq.test")
    for label, edit, said in (
            ("another release's archive", {"tag": "v1.312"}, "describes"),
            ("another schema state than the pin file's", {"highestMigration": f"V{STATE_C}"}, "schema state"),
            ("a record version the reader does not read", {"recordVersion": 9}, "record version"),
            ("a domain the lane's stack does not serve", {"domain": "family.example"}, "family.example")):
        why = refusal(l3.check_facts, {**good, **edit}, backup)
        check(f"facts: {label} is refused, naming it", said in why, why or "(accepted)")


# ---- 5. the archive, read ---------------------------------------------------------------------------------------

def fetch_cases() -> None:
    backup = l3.Backup("v1.260", STATE_D)
    digest = "sha256:" + "f" * 64
    calls: list[str] = []
    real = (archive_backup.lookup, archive_backup.pull, l3.cosign_verify)

    def stand_in(state: str, verifies: bool = True):
        archive_backup.lookup = lambda ref, plain: archive_backup.Lookup(
            state, digest if state == "present" else None, "said")
        archive_backup.pull = lambda ref, into, plain: (calls.append(f"pull {ref}"), {
            "tag": "v1.260", "highestMigration": f"V{STATE_D}", "recordVersion": 1, "domain": "veyq.test"})[1]

        def verify(ref: str, plain: bool) -> None:
            calls.append(f"verify {ref}")
            if not verifies:
                raise Red(f"{ref} does not verify as an archive")
        l3.cosign_verify = verify

    try:
        with tempfile.TemporaryDirectory() as d:
            stand_in("absent")
            why = refusal(l3.fetch, backup, "registry.test/o", False, pathlib.Path(d))
            check("archive: a selected backup that is not archived is red, naming the release and the dispatch",
                  "v1.260 is selected and not archived" in why and "archive.yml" in why and not calls, why)
            stand_in("unknown")
            why = refusal(l3.fetch, backup, "registry.test/o", False, pathlib.Path(d))
            check("archive: a registry that will not say is red, never a skip", "will not say" in why and not calls, why)
            stand_in("present", verifies=False)
            why = refusal(l3.fetch, backup, "registry.test/o", False, pathlib.Path(d))
            check("archive: a signature that does not verify under archive.yml's identity is refused before the pull",
                  "does not verify" in why and calls == [f"verify registry.test/o/veyq-backups@{digest}"], f"{why} {calls}")
            calls.clear()
            stand_in("present")
            got = l3.fetch(backup, "registry.test/o", False, pathlib.Path(d))
            check("archive: verified, then pulled by the digest that verified",
                  calls == [f"verify registry.test/o/veyq-backups@{digest}",
                            f"pull registry.test/o/veyq-backups@{digest}"] and got.digest == digest, str(calls))
    finally:
        archive_backup.lookup, archive_backup.pull, l3.cosign_verify = real
    seen: list[list[str]] = []
    real_run = l3.run
    try:
        l3.run = lambda *cmd, **kw: (seen.append(list(cmd)), type("R", (), {"returncode": 0, "stderr": ""})())[1]
        l3.cosign_verify("registry.test/o/veyq-backups@" + digest, False)
    finally:
        l3.run = real_run
    argv = seen[0] if seen else []
    check("archive: cosign verifies under archive_backup.py's issuer and identity, read from there",
          argv[:2] == ["cosign", "verify"] and argv[argv.index("--certificate-identity-regexp") + 1]
          == archive_backup.IDENTITY_REGEXP and argv[argv.index("--certificate-oidc-issuer") + 1]
          == archive_backup.OIDC_ISSUER, str(argv))


# ---- 6. one backup's red is its own -------------------------------------------------------------------------------

def blocking_cases() -> None:
    red = ["v1.0 restore: exited 1"]
    check("blocking: a backup's red skips its own later stages", l3.blocking(red, "v1.0 read") == red)
    check("blocking: and no other backup's", l3.blocking(red, "v1.168 archive") == [])
    shared = ["candidate: release_assets.py exited 1"]
    check("blocking: a shared stage's red skips every backup", l3.blocking(shared, "v1.168 archive") == shared)


def main() -> int:
    selection_cases()
    history_cases()
    shard_cases()
    facts_cases()
    fetch_cases()
    blocking_cases()
    if failures:
        print(f"[selftest] {len(failures)} failure(s)", file=sys.stderr)
        return 1
    print("[selftest] lane L3 selects one backup per schema state plus the major entry, splits it over its shards, and "
          "refuses an archive that is missing, foreign-signed or not the one selected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
