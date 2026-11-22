#!/usr/bin/env python3
"""No text a pull request's or a push's author chose reaches a shell through a `${{ }}` expression.

**What it asserts.** Over every `.github/workflows/*.y(a)ml` (found by reading the directory, never listed): no step's
`run:` and no `actions/github-script` step's `script:` holds a `${{ }}` expression that reads a context GitHub names
as attacker-controllable — a title, a body, a commit message, a branch name, an author's name or e-mail, a page name
(GitHub's *Security hardening for GitHub Actions* § Understanding the risk of script injections). Such an expression
is substituted into the script **before** the shell parses it, so a pull request titled `"; curl … | sh; "` runs as
code with the job's token. The safe form passes the value through `env:` and reads `"$VAR"`, or reads the event file
(`$GITHUB_EVENT_PATH`) from a script; `env:`, `with:` and `if:` are therefore not read here.

**The defect that bought it.** No incident. cr-release-draft-from-publish added the first workflow that reads a pull
request's body (`release-note.yml`, through the event file) and the first job of a publishing run that reads pull
request bodies through the API (`publish.yml`'s `release-draft`); the CR's invariant "a body reaches a script only
through the event file or the API" was a sentence until this made it a check. A bite before it existed: a
`run: echo "${{ github.event.pull_request.body }}"` appended to `release-note.yml` left every guard green.

**Its limits — what a green run does NOT prove.** It reads the expression's text, not its data flow: a value laundered
through a step's output (`${{ steps.x.outputs.title }}` set from a body) or a job output is not followed, and neither
is a body read from the API and then echoed unquoted by a script. A context missing from `ALWAYS`, `HEAD_ONLY` or `PERSON` is not seen.
Composite actions and reusable workflows outside `.github/workflows/` are not read (there are none today).
"""
from __future__ import annotations

import pathlib
import re
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

EXPRESSION = re.compile(r"\$\{\{(.*?)\}\}", re.DOTALL)
CONTEXT = re.compile(r"github\.[\w.*\[\]'\"-]+")
# GitHub's list of contexts an outsider controls, by the last part of their path under `github.event`.
ALWAYS = {"title", "body", "message", "page_name", "label"}
HEAD_ONLY = {"ref", "default_branch"}  # untrusted as a head's (a fork's branch), never the repository's own
PERSON = {"author", "committer", "pusher"}

errors: list[str] = []


def untrusted(path: str) -> bool:
    """Whether a context path (`github.event.pull_request.title`) is one an outsider controls."""
    if path == "github.head_ref" or path.startswith("github.head_ref."):
        return True
    if not path.startswith("github.event.") or path.startswith("github.event.repository."):
        return False
    parts = [x for x in re.split(r"[.\[\]'\"]+", path) if x]
    tail = parts[-1]
    if tail in ALWAYS:
        return True
    if tail in HEAD_ONLY:
        return "head" in parts[:-1]
    return tail in ("name", "email") and len(parts) > 1 and parts[-2] in PERSON




def scripts_of(step: dict) -> list[tuple[str, str]]:
    found = []
    if isinstance(step.get("run"), str):
        found.append(("run", step["run"]))
    if str(step.get("uses") or "").startswith("actions/github-script@"):
        script = (step.get("with") or {}).get("script")
        if isinstance(script, str):
            found.append(("with.script", script))
    return found


def untrusted_in(text: str) -> list[str]:
    return [m.group(0).strip() for m in EXPRESSION.finditer(text)
            if any(untrusted(c.group(0).rstrip(".")) for c in CONTEXT.finditer(m.group(1)))]


files = sorted(p for p in WORKFLOWS.glob("*.y*ml") if p.suffix in (".yml", ".yaml"))
steps_read = 0
for path in files:
    try:
        doc = yaml.safe_load(path.read_text()) or {}
    except yaml.YAMLError as e:
        errors.append(f"{path.relative_to(ROOT)}: does not parse ({e})")
        continue
    for job_id, job in (doc.get("jobs") or {}).items():
        for index, step in enumerate((job or {}).get("steps") or []):
            steps_read += 1
            for key, text in scripts_of(step or {}):
                for hit in untrusted_in(text):
                    name = (step or {}).get("name") or f"step {index + 1}"
                    errors.append(f"{path.relative_to(ROOT)} › {job_id} › {name}: `{key}` substitutes `{hit}` into the "
                                  f"script — pass it through `env:` and read \"$VAR\", or read $GITHUB_EVENT_PATH")

if not files:
    errors.append(f"no workflow found under {WORKFLOWS.relative_to(ROOT)} — the guard read nothing")

if errors:
    print("WORKFLOW INJECTION VALIDATION FAILED:")
    for e in errors:
        print("  -", e)
    sys.exit(1)

print(f"workflow injection OK: no run or github-script step of {len(files)} workflow(s) ({steps_read} steps) substitutes "
      f"an author-chosen context into its script")
