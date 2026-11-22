#!/usr/bin/env python3
"""Self-test of `scripts/release_notes.py`, offline: a pull request's note (read, refused, Dependabot exempt), the
draft's body (merged in order, Dependabot in one line, a missing note named, the operator default), its marker (an
edit is seen), what happens to the drafts (create, update, leave, none), the range since the last release on a
throwaway git history, and which pull request a commit maps to on recorded API shapes. `checks.yml`'s `guards` job runs
it.

    python3 scripts/release_notes_selftest.py

What it cannot see: GitHub. Whether `commits/<sha>/pulls` returns a squash-merged pull request for its merge commit, and
whether a draft's body comes back byte for byte, a run shows (the script's docstring).
"""
from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import release_notes as rn  # noqa: E402

failures: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        failures.append(message)


NOTE = """## Why

Engineering prose that never reaches the notes.

## Release note

<!-- Write it in the release's words. -->

**What is new**
- **A start screen.** While veyq starts,
  it shows its start screen.
- **It reloads itself once.**

**What an operator must act on**
- Set `VEYQ_X` before upgrading.

## What changed

- a class name
"""

# ---- the note in a pull request ---------------------------------------------------------------------------------------
got = rn.parse(rn.section(NOTE) or "")
check(got == {rn.NEW: ["- **A start screen.** While veyq starts,\n  it shows its start screen.",
                       "- **It reloads itself once.**"],
              rn.OPERATOR: ["- Set `VEYQ_X` before upgrading."]},
      f"a note is not read as its items under their headings, the next section and the comment left out: {got}")
check(rn.verdict(NOTE, "someone") == "", "a usable note is refused")
check(rn.verdict(NOTE.replace("\n", "\r\n"), "someone") == "", "a note with CRLF line ends (a browser's edit) is refused")
check(rn.verdict("## Release notes\n\nnone\n", "someone") == "" and rn.parse("None.") == {},
      "`none` is not a note, or `## Release notes` is not its heading")
check("no `## Release note`" in rn.verdict("## Why\n\nstuff\n", "someone"), "a body without the section passes")
check(rn.verdict(None, "someone") != "", "an empty body passes")
check(rn.verdict("## Release note\n\n<!-- guidance only -->\n", "someone") != "", "a section holding only the template's comment passes")
check(rn.verdict("## Release note\n\n**What is new**\n\n**Fixed**\n", "someone") != "", "headings without an item pass")
check(rn.verdict("## Release note\n\n- an item before any heading\n", "someone") != "", "an item before any heading passes")
check(rn.verdict("## Release note\n\n**Changed**\n- x\n", "someone") != "", "an unknown heading passes")
check(rn.verdict("## Release note\n\n**Fixed**\nprose, no item\n", "someone") != "", "prose under a heading passes")
check(rn.verdict("## Release note\n\n**fixed:**\n- x\n", "someone") == "", "a heading's case or a colon refuses the note")
check(rn.verdict("", "dependabot[bot]") == "", "Dependabot's pull request needs a note")
ATTRIBUTION = "\n---\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"
check(rn.verdict("## Release note\n\nnone\n" + ATTRIBUTION, "someone") == "",
      "a `none` note above a thematic break and the attribution line is refused")
check(rn.parse(rn.section(NOTE.replace("## What changed", ATTRIBUTION + "## What changed")) or "")
      == rn.parse(rn.section(NOTE) or ""), "a thematic break does not end the section")
check(rn.verdict("## Release note\n\nnone\n\n🤖 Generated with x\n", "someone") != "",
      "prose after the note without a break is taken as part of `none`")
check(rn.verdict("", "dependabot-impostor") != "", "an author that only looks like Dependabot is exempt")

with tempfile.TemporaryDirectory(prefix="release_notes_event_") as tmp:
    event = pathlib.Path(tmp) / "event.json"
    event.write_text(json.dumps({"pull_request": {"number": 7, "body": "no note", "user": {"login": "someone"}}}))
    proc = subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve().parent / "release_notes.py"), "check",
                           "--event", str(event)], capture_output=True, text=True)
    check(proc.returncode == 1 and "::error::#7" in proc.stdout, f"`check` on a body without a note is not red: {proc}")
    event.write_text(json.dumps({"pull_request": {"number": 8, "body": NOTE, "user": {"login": "someone"}}}))
    proc = subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve().parent / "release_notes.py"), "check",
                           "--event", str(event)], capture_output=True, text=True)
    check(proc.returncode == 0, f"`check` on a usable note is red: {proc}")

# ---- the draft's body -------------------------------------------------------------------------------------------------
pulls = [
    {"number": 40, "title": "fix(web): a", "author": "someone",
     "body": "## Release note\n\n**Fixed**\n- A Memory is not numbered (2).\n"},
    {"number": 22, "title": "chore(deps): b", "author": "dependabot[bot]", "body": "Bumps …"},
    {"number": 41, "title": "ci: c", "author": "someone", "body": "## Release note\n\nnone\n"},
    {"number": 42, "title": "feat: d", "author": "someone", "body": NOTE},
    {"number": 43, "title": "fix: e", "author": "someone", "body": "no section"},
    {"number": 36, "title": "chore(deps): f", "author": "dependabot[bot]", "body": ""},
]
body = rn.assemble(pulls)
want_order = ["**What is new**", "- **A start screen.**", "**Fixed**", "- A Memory is not numbered (2).",
              "**Under the hood**", "- Dependencies updated: #22, #36.", "**Needs a note**", "- #43 fix: e —",
              "**What an operator must act on**", "- Set `VEYQ_X` before upgrading.", rn.FOOTER, "<!-- veyq-release-draft"]
at = [body.find(s) for s in want_order]
check(all(i >= 0 for i in at) and at == sorted(at), f"the draft's body is not in the house order: {body}")
check("#41" not in body and "class name" not in body and "Engineering prose" not in body,
      f"a `none` pull request or the engineering sections reach the notes: {body}")
plain = rn.assemble([pulls[0]])
check("**What an operator must act on**\n\n- Nothing." in plain and "**What is new**" not in plain
      and "**Under the hood**" not in plain and "**Needs a note**" not in plain,
      f"an operator section is not `Nothing.` by default, or an empty heading is written: {plain}")
check(rn.untouched(body), "the script's own body reads as edited")
check(rn.untouched(body.replace("\n", "\r\n")), "the script's own body with CRLF line ends reads as edited")
check(not rn.untouched(body.replace("A Memory", "One Memory")), "an edited body reads as untouched")
check(not rn.untouched(body + "\n\n<!-- veyq-promote-refused -->\n> **Promotion refused**: x\n"),
      "a draft carrying release.yml's refusal reads as untouched")
check(not rn.untouched("a draft a person wrote"), "a draft without the marker reads as untouched")
check(not rn.untouched(None), "an empty draft reads as untouched")

# ---- what happens to the drafts ---------------------------------------------------------------------------------------
TAG = "snapshot-1.250"
got = rn.plan([], TAG, "veyq 1.250", body)
check(got["action"] == "create" and got["payload"] == {"tag_name": TAG, "name": "veyq 1.250", "body": body, "draft": True},
      f"no draft does not create one on the snapshot's tag: {got}")
mine = {"id": 5, "tag_name": "snapshot-1.249", "body": rn.assemble([pulls[0]])}
got = rn.plan([mine], TAG, "veyq 1.250", body)
check(got["action"] == "update" and got["draft_id"] == "5" and got["payload"]["tag_name"] == TAG,
      f"an untouched draft is not moved onto the new snapshot: {got}")
got = rn.plan([{"id": 5, "tag_name": TAG, "body": body}], TAG, "veyq 1.250", body)
check(got["action"] == "none", f"a draft that already holds these notes is written again: {got}")
got = rn.plan([{"id": 5, "tag_name": "snapshot-1.249", "body": "the owner's own words"}], TAG, "veyq 1.250", body)
check(got["action"] == "leave" and got["draft_id"] == "5", f"an edited draft is overwritten: {got}")
got = rn.plan([mine, dict(mine, id=6)], TAG, "veyq 1.250", body)
check(got["action"] == "leave", f"two drafts are not left alone: {got}")
check(all(rn.plan(d, TAG, "n", body)["payload"].get("draft") is True for d in ([], [mine])),
      "a payload publishes the release")

# ---- which pull request a commit is -----------------------------------------------------------------------------------
SHA, OTHER = "a" * 40, "b" * 40
shapes = {f"repos/o/veyq/commits/{SHA}/pulls": [
    {"number": 3, "merged_at": None, "base": {"ref": "main"}, "merge_commit_sha": SHA, "title": "open", "user": {"login": "x"}},
    {"number": 4, "merged_at": "2026-11-20T10:00:00Z", "base": {"ref": "release/1.x"}, "merge_commit_sha": SHA,
     "title": "other branch", "user": {"login": "x"}},
    {"number": 5, "merged_at": "2026-11-20T10:00:00Z", "base": {"ref": "main"}, "merge_commit_sha": OTHER,
     "title": "merged as another commit", "user": {"login": "x"}},
    {"number": 6, "merged_at": "2026-11-20T10:00:00Z", "base": {"ref": "main"}, "merge_commit_sha": SHA,
     "title": "the one", "user": {"login": "someone"}, "body": "b"}]}
got = rn.pull_of("o/veyq", SHA, "main", lambda url: shapes.get(url, []))
check(got == {"number": 6, "title": "the one", "author": "someone", "body": "b"},
      f"a commit maps to an open, another branch's or another commit's pull request: {got}")
check(rn.pull_of("o/veyq", OTHER, "main", lambda url: shapes.get(url, [])) is None, "a commit without one maps to one")


# ---- the range, on a throwaway history --------------------------------------------------------------------------------
def git(cwd: pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", "-c", "user.name=selftest", "-c", "user.email=selftest@invalid", "-c",
                           "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


with tempfile.TemporaryDirectory(prefix="release_notes_range_") as tmp:
    repo = pathlib.Path(tmp)
    git(repo, "init", "-q", "-b", "main")
    shas = []
    for n in range(6):
        (repo / "f").write_text(f"{n}\n")
        git(repo, "add", "f")
        git(repo, "commit", "-q", "-m", f"c{n}")
        shas.append(git(repo, "rev-parse", "HEAD"))
    git(repo, "tag", "v1.9", shas[0])
    git(repo, "tag", "v1.10", shas[2])  # newer by number, though "v1.9" sorts after it as text
    git(repo, "tag", "v2.0", shas[3])   # another major
    git(repo, "tag", "v1.10-rc", shas[4])
    check(rn.last_release(repo, shas[5], 1) == "v1.10", f"the last release is not the newest v1.* ancestor: "
                                                         f"{rn.last_release(repo, shas[5], 1)}")
    check(rn.last_release(repo, shas[1], 1) == "v1.9", "a release after the commit is taken as its last")
    check(rn.last_release(repo, shas[5], 3) is None, "a major without a release finds one")
    check(rn.commits_since(repo, "v1.10", shas[5]) == shas[3:6], "the range is not the commits after the release, oldest first")

    pulls_by_sha = {shas[3]: 11, shas[5]: 12}
    world = {f"repos/o/veyq/commits/{s}/pulls": [{"number": n, "merged_at": "x", "base": {"ref": "main"},
                                                  "merge_commit_sha": s, "title": f"t{n}", "user": {"login": "someone"},
                                                  "body": f"## Release note\n\n**Fixed**\n- fix {n}.\n"}]
             for s, n in pulls_by_sha.items()}
    world["repos/o/veyq/releases?per_page=100"] = [{"id": 1, "draft": False, "tag_name": "v1.10", "body": ""}]
    got, summary = rn.draft("o/veyq", "1.11", shas[5], "main", repo, lambda url: world.get(url, []))
    body = got["payload"]["body"]
    check(got["action"] == "create" and body.find("- fix 11.") < body.find("- fix 12.") and body.find("- fix 11.") >= 0,
          f"the draft does not gather the range's notes in merge order: {got}")
    check(any(shas[4][:12] in line for line in summary) and shas[4][:12] not in body,
          f"a commit without a pull request is not named in the summary alone: {summary}")
    got, _ = rn.draft("o/veyq", "3.0", shas[5], "main", repo, lambda url: world.get(url, []))
    check(got["action"] == "leave", f"the first release of a major is drafted: {got}")
    for bad in (("1.11", shas[5], "release/1.x"), ("1.11-SNAPSHOT", shas[5], "main"), ("1.11", "HEAD", "main")):
        try:
            rn.draft("o/veyq", bad[0], bad[1], bad[2], repo, lambda url: world.get(url, []))
            failures.append(f"a draft for {bad} is planned")
        except rn.Refused:
            pass

if failures:
    print("RELEASE NOTES SELFTEST FAILED:")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("release notes selftest OK: a pull request's note is read, refused or exempt as it should; the draft gathers the "
      "range's notes in the house order and sees an edit; the drafts are created, moved or left as release.yml needs")
