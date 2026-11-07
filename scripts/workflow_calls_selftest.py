#!/usr/bin/env python3
"""Offline self-test of `workflow_calls.py`, the one reading of a reusable-workflow call (ADR 0091 decision 2).

Each case is an inline caller and called workflow, read through a fake `read`, so nothing here depends on the tree's
workflows: the rules are held on their own, and every reader that imports the module inherits them. A case either
asserts what `expand()`/`calls()` give or that a `CallError` names the refusal. Run by the pipeline's `guards` job.
"""
from __future__ import annotations  # `X | None` in annotations — scripts run on the host's Python 3.9

import sys
import textwrap

import workflow_calls as wc

FAILED: list = []


def check(name: str, ok: bool, detail: str = "") -> None:
    print(f"{'ok  ' if ok else 'FAIL'} {name}{'' if ok else ' — ' + detail}")
    if not ok:
        FAILED.append(name)


def y(text: str) -> dict:
    return wc.parse(textwrap.dedent(text))


CALLED = """
    on:
      workflow_call:
        inputs:
          publish: {type: boolean, default: false}
        outputs:
          kind: {value: "${{ jobs.version.outputs.kind }}"}
    env:
      HELM: v4
    jobs:
      version:
        runs-on: ubuntu-latest
        permissions: {contents: read}
        steps: [{run: "true"}]
      build:
        needs: version
        runs-on: ubuntu-latest
        steps: [{run: "true"}]
      test:
        runs-on: ubuntu-latest
        permissions: {contents: read}
        steps: [{run: "true"}]
"""

CALLER = """
    on: {push: {}}
    env:
      CALLER_ONLY: x
    permissions: {contents: read}
    jobs:
      lint:
        runs-on: ubuntu-latest
        steps: [{run: "true"}]
      pipeline:
        needs: lint
        uses: ./.github/workflows/pipeline.yml
        permissions: {contents: read, packages: write}
        with: {publish: true}
      after:
        needs: pipeline
        if: needs.pipeline.outputs.kind == 'snapshot'
        runs-on: ubuntu-latest
        permissions: {contents: read, id-token: write}
        steps: [{run: "true"}]
"""


def reader(files: dict):
    def read(rel: str) -> str:
        if rel not in files:
            raise FileNotFoundError(rel)
        return textwrap.dedent(files[rel])
    return read


def refused(name: str, caller: str, called: str | None, fragment: str) -> None:
    files = {} if called is None else {".github/workflows/pipeline.yml": called}
    try:
        wc.expand(y(caller), reader(files), "caller.yml")
    except wc.CallError as e:
        check(name, fragment in str(e), f"refused, but for: {e}")
        return
    check(name, False, "expand() accepted it")


def main() -> int:
    read = reader({".github/workflows/pipeline.yml": CALLED})
    jobs = {j.id: j for j in wc.expand(y(CALLER), read, "caller.yml")}

    check("call job replaced by the called jobs, in file order",
          list(jobs) == ["lint", "pipeline/version", "pipeline/build", "pipeline/test", "after"], str(list(jobs)))
    v, b, t, a = jobs["pipeline/version"], jobs["pipeline/build"], jobs["pipeline/test"], jobs["after"]
    check("a called job knows its file and call job", v.file == ".github/workflows/pipeline.yml" and v.call == "pipeline"
          and v.local == "version", repr(v[:5]))
    check("inputs: `with:` over the declared default", v.inputs == {"publish": True}, str(v.inputs))
    check("env: the called file's own, never the caller's", v.env == {"HELM": "v4"}, str(v.env))
    check("permissions: a called job's own block stands", v.permissions == {"contents": "read"}, str(v.permissions))
    check("permissions: a called job without a block gets the call job's grant",
          b.permissions == {"contents": "read", "packages": "write"}, str(b.permissions))
    check("needs: a called job's own need is qualified, its expression name kept",
          b.needs == ("pipeline/version",) and b.needs_names == {"pipeline/version": "version"}, f"{b.needs} {b.needs_names}")
    check("needs: a called root waits for what the call job needs", v.needs == ("lint",) and t.needs == ("lint",),
          f"{v.needs} {t.needs}")
    check("needs: a caller job that needs the call job waits for every called job",
          set(a.needs) == {"pipeline/version", "pipeline/build", "pipeline/test"}, str(a.needs))
    check("permissions: a caller job's own block stands", a.permissions == {"contents": "read", "id-token": "write"},
          str(a.permissions))
    check("permissions: a caller job without a block gets the caller's top level",
          jobs["lint"].permissions == {"contents": "read"}, str(jobs["lint"].permissions))

    info = wc.calls(y(CALLER), read, "caller.yml")["pipeline"]
    check("calls(): outputs map name -> value expression",
          info["outputs"] == {"kind": "${{ jobs.version.outputs.kind }}"}, str(info["outputs"]))
    check("calls(): the called job ids", info["jobs"] == ["pipeline/version", "pipeline/build", "pipeline/test"],
          str(info["jobs"]))

    # The refusals: each is a run GitHub would refuse, or one this module would otherwise misread.
    over = CALLED.replace("permissions: {contents: read}\n        steps", "permissions: {contents: write}\n        steps", 1)
    refused("a called job asking above the grant is refused", CALLER, over, "asks for more than")
    over_top = CALLED.replace("env:\n      HELM", "permissions: {id-token: write}\n    env:\n      HELM")
    refused("a called workflow's top level asking above the grant is refused", CALLER, over_top, "top-level permissions")
    refused("another repository's workflow is refused",
            CALLER.replace("./.github/workflows/pipeline.yml", "octo/repo/.github/workflows/p.yml@main"), CALLED,
            "only a workflow of this repository")
    nested = CALLED.replace("      test:\n        runs-on: ubuntu-latest\n        permissions: {contents: read}\n"
                            "        steps: [{run: \"true\"}]", "      test:\n        uses: ./.github/workflows/x.yml")
    refused("a call inside a called workflow is refused", CALLER, nested, "call inside a called workflow")
    refused("a called file without workflow_call is refused", CALLER, CALLED.replace("workflow_call", "push"),
            "no `on.workflow_call`")
    refused("an undeclared `with:` key is refused", CALLER.replace("{publish: true}", "{publish: true, x: 1}"), CALLED,
            "does not declare")
    required = CALLED.replace("{type: boolean, default: false}", "{type: boolean, required: true}")
    refused("a required input left out is refused", CALLER.replace("        with: {publish: true}\n", ""), required,
            "required input")

    # No ceiling where nothing grants one: the repository's default is not known here, so nothing is checked.
    free = CALLER.replace("permissions: {contents: read}\n    jobs", "jobs").replace(
        "        permissions: {contents: read, packages: write}\n", "")
    jobs_free = {j.id: j for j in wc.expand(y(free), reader({".github/workflows/pipeline.yml": over}), "c.yml")}
    check("no grant anywhere: no ceiling, and a block-less called job has the default (None)",
          jobs_free["pipeline/build"].permissions is None, str(jobs_free["pipeline/build"].permissions))

    print(f"{'FAILED: ' + ', '.join(FAILED) if FAILED else 'workflow_calls selftest OK'}")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
