#!/usr/bin/env python3
"""A release's notes, written where the work is fresh — in each pull request — and the weekly draft that gathers them
(cr-release-draft-from-publish; ADR 0091 decision 5).

    python3 scripts/release_notes.py check --event "$GITHUB_EVENT_PATH"
    python3 scripts/release_notes.py draft --repository <owner>/<repo> --version <major>.<minor> --commit <sha> \\
        --branch main --out <dir>

**The note.** Every pull request's body carries a section `## Release note`, in the release's own words (the Use
area's tone: what a person sees, never a class name), under any of three bold headings, each followed by list items:

    ## Release note

    **What is new**
    - **A start screen instead of a white page.** While veyq starts, …

    **Fixed**
    - …

    **What an operator must act on**
    - …

or the single word `none` when nobody using or running veyq would notice the change (CI, docs, a refactor). HTML
comments are ignored, so the pull request template's guidance may stay in place; the section ends at the next `#`/`##`
heading or at a thematic break (`---`), below which a body may close with lines that are no note (an attribution).
Dependabot's pull requests need none.

**check** — `release-note.yml` runs it on every pull request event: red, naming what is wrong, unless the body has a
usable note or the author is Dependabot. It reads the body from the event file only (never from a `${{ }}` expression
in a run line, where a body would be shell).

**draft** — `publish.yml`'s `release-draft` job runs it after the snapshot is tagged, on `main` only. It decides and
writes no release itself: the job's own lines do the one `gh api` write (create or edit a draft), where
`scripts/validate_release_identity.py` sees it. It reads:

  1. **The range**: from the newest `v<major>.<m>` tag that is an ancestor of the snapshot's commit to that commit,
     first-parent, oldest first. No such tag: no draft (the first release of a major gets its notes by hand).
  2. **Each commit's pull request** (`commits/<sha>/pulls`, the one merged into the branch as that commit). Its note's
     items go under their heading in merge order; `none` adds nothing; Dependabot's become one line under **Under the
     hood**; one without a usable note is named under **Needs a note**, visible to the owner who reads the draft; a
     commit without a pull request is named in the job summary only.
  3. **The drafts**: `release.yml` promotes only when exactly one draft exists (promote.py check 1), so this keeps it at
     one. None → `create`. One whose body still carries this script's marker with a matching hash — untouched since
     it was written — → `update`: moved onto the new snapshot with notes from the same last release, so nothing it
     named is lost. One a person edited, or that carries `release.yml`'s refusal (either breaks the hash) → `leave`;
     so does more than one draft. Nothing is ever deleted here.

It writes `<out>/plan.json` (`action`: create | update | leave | none, `draft_id`, `reason`) and `<out>/payload.json`
(the release's `tag_name`, `name`, `body`, `draft: true` — never `draft: false`: publishing stays `release.yml`'s).

**No lede.** Past notes opened with one sentence summing the release up. Summing several pull requests up is a
judgement; the owner adds it while reading the draft when wanted (cr-release-draft-from-publish, decision open to the
owner's override).

**What a green selftest does not prove** (`scripts/release_notes_selftest.py`): that GitHub's `commits/<sha>/pulls`
returns a squash-merged pull request for its merge commit, and that a draft's body comes back from the API byte for
byte as it was sent — a run shows both; a body that came back changed would read as edited and be left, never
overwritten.
"""
from __future__ import annotations  # `X | None` — the repo's scripts run on Python 3.9

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

NOTE_HEADING = re.compile(r"^##\s+release\s+notes?\s*$", re.IGNORECASE)
NEXT_SECTION = re.compile(r"^(?:#{1,2}\s|\s{0,3}(?:-\s*){3,}$|\s{0,3}(?:\*\s*){3,}$|\s{0,3}(?:_\s*){3,}$)")
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
SUBHEADING = re.compile(r"^\*\*(.+?)\*\*\s*$")
HEADINGS = ("What is new", "Fixed", "What an operator must act on")
NEW, FIXED, OPERATOR = HEADINGS
UNDER_THE_HOOD, NEEDS_A_NOTE = "Under the hood", "Needs a note"
FOOTER = ("As every release, it is signed by `release.yml`; [Verify a release](https://veyq.app/run/verify-a-release/) "
          "says how to check that yourself.")
MARKER = re.compile(r"\n*<!-- veyq-release-draft sha256:([0-9a-f]{64}) -->\s*$")
RELEASE_TAG = re.compile(r"^v([0-9]+)\.([0-9]+)$")
VERSION = re.compile(r"^([0-9]+)\.([0-9]+)$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")


class Refused(Exception):
    pass


# ---- the note in a pull request ---------------------------------------------------------------------------------------

def section(body: str | None) -> str | None:
    """The text under `## Release note`, up to the next `#`/`##` heading or thematic break (`---`), comments removed;
    None without the heading. The break lets a body end in a line that is no note — the attribution line every pull
    request a Claude session opens must end with sits below one."""
    lines = COMMENT.sub("", (body or "").replace("\r\n", "\n")).split("\n")
    for i, line in enumerate(lines):
        if NOTE_HEADING.match(line.strip()):
            rest = []
            for following in lines[i + 1:]:
                if NEXT_SECTION.match(following):
                    break
                rest.append(following)
            return "\n".join(rest).strip()
    return None


def parse(text: str) -> dict[str, list[str]] | str:
    """A note's items per heading, `{}` for `none`; a string is why it is not a note."""
    if text.strip().rstrip(".").lower() == "none":
        return {}
    items: dict[str, list[str]] = {}
    heading: str | None = None
    for line in text.split("\n"):
        if not line.strip():
            continue
        sub = SUBHEADING.match(line.strip())
        if sub and not line.startswith((" ", "\t", "-", "*  ")):
            name = sub.group(1).strip().rstrip(":")
            match = [h for h in HEADINGS if h.lower() == name.lower()]
            if not match:
                return f"`**{name}**` is no heading of a note (one of {', '.join(HEADINGS)})"
            heading = match[0]
            items.setdefault(heading, [])
            continue
        if heading is None:
            return f"`{line.strip()[:60]}` stands before any heading ({', '.join(HEADINGS)})"
        if re.match(r"^[-*]\s+\S", line):
            items[heading].append("- " + re.sub(r"^[-*]\s+", "", line).rstrip())
        elif re.match(r"^\s+\S", line) and items[heading]:
            items[heading][-1] += "\n" + line.rstrip()
        else:
            return f"`{line.strip()[:60]}` under **{heading}** is no list item (`- …`)"
    if not any(items.values()):
        return "the section names no item (write `none` when nobody would notice the change)"
    return {h: v for h, v in items.items() if v}


def is_dependabot(author: str) -> bool:
    return author in ("dependabot[bot]", "dependabot-preview[bot]")


def verdict(body: str | None, author: str) -> str:
    """'' when the pull request may merge as far as its note goes, else why not."""
    if is_dependabot(author):
        return ""
    text = section(body)
    if text is None:
        return ("the body has no `## Release note` section — add one in the release's own words, or `none` when nobody "
                "using or running veyq would notice (scripts/release_notes.py says the form)")
    got = parse(text)
    return got if isinstance(got, str) else ""


# ---- the draft --------------------------------------------------------------------------------------------------------

def normalized(text: str) -> str:
    return "\n".join(line.rstrip() for line in text.replace("\r\n", "\n").strip().split("\n"))


def marker(text: str) -> str:
    return f"<!-- veyq-release-draft sha256:{hashlib.sha256(normalized(text).encode()).hexdigest()} -->"


def untouched(body: str | None) -> bool:
    """Whether a draft's body is exactly what this script wrote: its marker's hash matches the text above it."""
    found = MARKER.search((body or "").replace("\r\n", "\n"))
    if not found:
        return False
    return marker(body.replace("\r\n", "\n")[:found.start()]) == f"<!-- veyq-release-draft sha256:{found.group(1)} -->"


def assemble(pulls: list[dict]) -> str:
    """The draft's body from the range's pull requests (`number`, `title`, `author`, `body`), in merge order."""
    merged: dict[str, list[str]] = {h: [] for h in HEADINGS}
    dependencies: list[int] = []
    missing: list[str] = []
    for pull in pulls:
        if is_dependabot(str(pull.get("author") or "")):
            dependencies.append(int(pull["number"]))
            continue
        text = section(pull.get("body"))
        got = parse(text) if text is not None else "no `## Release note` section"
        if isinstance(got, str):
            missing.append(f"- #{pull['number']} {pull.get('title') or ''}".rstrip() + f" — {got}")
            continue
        for heading, items in got.items():
            merged[heading].extend(items)
    parts: list[tuple[str, list[str]]] = [(NEW, merged[NEW]), (FIXED, merged[FIXED])]
    if dependencies:
        parts.append((UNDER_THE_HOOD, ["- Dependencies updated: " + ", ".join(f"#{n}" for n in dependencies) + "."]))
    if missing:
        parts.append((NEEDS_A_NOTE, missing))
    parts.append((OPERATOR, merged[OPERATOR] or ["- Nothing."]))
    text = "\n\n".join(f"**{heading}**\n\n" + "\n".join(items) for heading, items in parts if items)
    text = f"{text}\n\n{FOOTER}"
    return f"{text}\n\n{marker(text)}\n"


def plan(drafts: list[dict], tag: str, name: str, body: str) -> dict:
    """What to do with the drafts (each `id`, `tag_name`, `body`) so that exactly one names this snapshot."""
    payload = {"tag_name": tag, "name": name, "body": body, "draft": True}
    if not drafts:
        return {"action": "create", "draft_id": "", "reason": f"no draft exists: one is created on {tag}",
                "payload": payload}
    if len(drafts) > 1:
        ids = ", ".join(str(d.get("id")) for d in drafts)
        return {"action": "leave", "draft_id": "", "payload": payload,
                "reason": f"{len(drafts)} drafts exist ({ids}); release.yml promotes only with one — none is touched"}
    draft = drafts[0]
    if not untouched(draft.get("body")):
        return {"action": "leave", "draft_id": str(draft.get("id")), "payload": payload,
                "reason": f"the draft on {draft.get('tag_name')} was edited (by a person, or release.yml's refusal) — "
                          f"it waits for the owner and is not overwritten"}
    if draft.get("tag_name") == tag and normalized(draft.get("body") or "") == normalized(body):
        return {"action": "none", "draft_id": str(draft.get("id")), "payload": payload,
                "reason": f"the draft on {tag} already holds these notes"}
    return {"action": "update", "draft_id": str(draft.get("id")), "payload": payload,
            "reason": f"the untouched draft on {draft.get('tag_name')} moves to {tag} with the notes since the last release"}


# ---- the world: git and the API ---------------------------------------------------------------------------------------

def _git(root: pathlib.Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True).stdout.strip()


def _gh_json(*args: str):
    proc = subprocess.run(["gh", "api", *args], capture_output=True, text=True)
    if proc.returncode != 0:
        raise Refused(f"`gh api {' '.join(args)}` failed: {(proc.stderr or proc.stdout).strip()[-300:]}")
    return json.loads(proc.stdout)


def last_release(root: pathlib.Path, commit: str, major: int) -> str | None:
    """The newest `v<major>.<m>` tag that is an ancestor of `commit` (or names it)."""
    found = []
    for tag in _git(root, "tag", "--list", f"v{major}.*", "--merged", commit).split():
        m = RELEASE_TAG.match(tag)
        if m and int(m.group(1)) == major:
            found.append((int(m.group(2)), tag))
    return max(found)[1] if found else None


def commits_since(root: pathlib.Path, since: str, commit: str) -> list[str]:
    return list(reversed(_git(root, "rev-list", "--first-parent", f"{since}..{commit}").split()))


def pull_of(repository: str, sha: str, branch: str, gh=_gh_json) -> dict | None:
    """The pull request merged into `branch` as `sha`, or None."""
    for pull in gh(f"repos/{repository}/commits/{sha}/pulls") or []:
        if not pull.get("merged_at") or ((pull.get("base") or {}).get("ref")) != branch:
            continue
        if pull.get("merge_commit_sha") not in (None, sha):
            continue
        return {"number": pull.get("number"), "title": pull.get("title") or "",
                "author": ((pull.get("user") or {}).get("login")) or "", "body": pull.get("body") or ""}
    return None


def drafts_of(repository: str, gh=_gh_json) -> list[dict]:
    return [r for r in gh(f"repos/{repository}/releases?per_page=100") or [] if r.get("draft")]


def draft(repository: str, version: str, commit: str, branch: str, root: pathlib.Path, gh=_gh_json) -> tuple[dict, list[str]]:
    """The plan and the summary lines (a commit without a pull request is named there)."""
    m = VERSION.match(version)
    if not m:
        raise Refused(f"`{version}` is no <major>.<minor> version")
    if not COMMIT.match(commit):
        raise Refused(f"`{commit}` is not a full commit hash")
    if branch != "main":
        raise Refused(f"`{branch}`: a draft is written for `main` only; a fix release on a release branch is drafted by hand")
    since = last_release(root, commit, int(m.group(1)))
    if since is None:
        return ({"action": "leave", "draft_id": "", "payload": {},
                 "reason": f"no v{m.group(1)}.* release precedes {commit}: the first release of a major is drafted by hand"},
                [])
    pulls, summary = [], []
    for sha in commits_since(root, since, commit):
        pull = pull_of(repository, sha, branch, gh)
        if pull is None:
            summary.append(f"{sha[:12]} has no pull request merged into {branch}: it is in the release, not in its notes")
        else:
            pulls.append(pull)
    body = assemble(pulls)
    got = plan(drafts_of(repository, gh), f"snapshot-{version}", f"veyq {version}", body)
    summary.insert(0, f"notes from {len(pulls)} pull request(s) since {since}: {got['reason']}")
    return got, summary


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("check", help="a pull request's body carries a usable release note")
    c.add_argument("--event", required=True, type=pathlib.Path)
    d = sub.add_parser("draft", help="the weekly draft's plan and payload")
    d.add_argument("--repository", required=True)
    d.add_argument("--version", required=True)
    d.add_argument("--commit", required=True)
    d.add_argument("--branch", required=True)
    d.add_argument("--out", required=True, type=pathlib.Path)
    args = parser.parse_args(argv)
    if args.cmd == "check":
        event = json.loads(args.event.read_text())
        pull = event.get("pull_request") or {}
        why = verdict(pull.get("body"), ((pull.get("user") or {}).get("login")) or "")
        if why:
            print(f"::error::#{pull.get('number')}: {why}")
            return 1
        print(f"#{pull.get('number')}: the release note is usable")
        return 0
    try:
        got, summary = draft(args.repository, args.version, args.commit, args.branch,
                             pathlib.Path(__file__).resolve().parent.parent)
    except Refused as e:
        print(f"::error::{e}")
        return 1
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "payload.json").write_text(json.dumps(got.pop("payload")))
    (args.out / "plan.json").write_text(json.dumps(got))
    for line in summary:
        print(line)
    if got["action"] == "leave":
        print(f"::notice::{got['reason']}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
