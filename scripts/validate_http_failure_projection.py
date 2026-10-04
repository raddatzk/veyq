#!/usr/bin/env python3
"""Guard the single-source HTTP failure projection (audit #72 / P3b, ADR 0042).

Every backend operation's `…Outcome` failure variant projects to the wire `ErrorResponse{code}` through the
**one** `core/http/` envelope writer (`httpError`), never a per-resource `Response.status(...).entity(
ErrorResponse(...))` or a hand-rolled `error()/forbidden()/notFound()` helper. That is what keeps the status +
code single-sourced (on the variant / the `Failures` catalog) and makes drift structurally impossible.

The enforceable invariant, promoted from cr-0161's one-shot proof grep to a permanent check:
  `ErrorResponse(` is CONSTRUCTED in exactly one file — the shared writer `core/http/HttpFailure.kt`.

Any other `backend/src/main` file that constructs an `ErrorResponse` has bypassed the shared writer and can drift
the status/code for a failure the rest of the codebase routes through `httpError` — so it fails this guard.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
WRITER = backend_tree.source_file("com.veyq.core.http.HttpFailure")

# An `ErrorResponse(` not preceded by an identifier char / dot — i.e. a constructor call, not `SomeErrorResponse(`
# or `x.ErrorResponse(`.
CONSTRUCT = re.compile(r"(?<![\w.])ErrorResponse\s*\(")

errors: list[str] = []


def main() -> int:
    if not backend_tree.main_roots():
        print(f"no backend sources found under {backend_tree.BACKEND.relative_to(ROOT)}/**/src/main/kotlin")
        return 1

    writer_hits = 0
    for path in backend_tree.main_kotlin():
        hits = [
            i + 1
            for i, line in enumerate(path.read_text().splitlines())
            if CONSTRUCT.search(line)
        ]
        if not hits:
            continue
        if path == WRITER:
            writer_hits = len(hits)
            continue
        rel = path.relative_to(ROOT)
        for ln in hits:
            errors.append(
                f"{rel}:{ln} constructs ErrorResponse directly — route the failure through the shared "
                f"`httpError(HttpFailure)` writer (core/http/) instead (ADR 0042)")

    if writer_hits == 0:
        errors.append(
            f"the shared writer {WRITER.relative_to(ROOT)} constructs no ErrorResponse — the guard's anchor is "
            f"gone; the projection primitive moved or broke (ADR 0042)")

    if errors:
        print("HTTP FAILURE PROJECTION VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1
    print(
        "http failure projection OK: ErrorResponse is constructed only in "
        f"{WRITER.relative_to(ROOT)} ({writer_hits} site) — every failure routes through the shared writer")
    return 0


if __name__ == "__main__":
    sys.exit(main())
