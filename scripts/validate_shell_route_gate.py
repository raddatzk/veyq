#!/usr/bin/env python3
"""Guard that no app-shell route is session-gated but not unlock-gated (uc.account.sessions §E3, ADR 0004).

**What it asserts.** Every route object declared inline in `web/src/router.ts` that carries `layout: 'app'`
also carries `requiresUnlock: true`. The shell is where content lives, and content is only readable through an
unlocked vault, so a shell route reachable on a session alone is a route rendering panels whose data cannot
exist — or worse, one that exposes a surface designed for an unlocked caller to a locked one. `requiresUnlock`
is the router's single unlock gate (`beforeEach`); `layout: 'app'` is what puts a view inside the shell. Either
a route is both, or it is neither.

**The defect that bought it.** No incident, and that is the point: this is the shortcut inbox #111 refused
**twice** while designing the locked sessions entry. Dropping `requiresUnlock` from `/settings/:section?` would
have let a locked device reach the sessions panel — and every other settings panel with it. A dedicated
locked-reachable route for that one panel would have introduced the first session-gated-but-not-unlock-gated
route in this router: a new security class, minted to serve an action that needs no panel at all. E3's whole
shape — a secondary action on the sign-in *form*, one call, no route — exists to avoid that, and until this
guard nothing but prose stopped the next reader from taking the shortcut anyway.

**Its limit.** Two wrong shapes bound it, and both are recorded because each is the first thing somebody
reaches for. A **line-based** probe (grep `layout: 'app'`, check the same or a nearby line for
`requiresUnlock`) produced a **false positive on a comment** carrying the phrase — routes are objects spanning
several lines with their flags in a nested `meta`, so the anchor has to be the object, not the line. And the
first object-anchored version blanked **string bodies** wholesale to keep the brace matching honest, which
blanked `'app'` itself: it reported *zero* shell routes and passed every forgery. Only the structural
characters inside a string are blanked now (see `_STRING_HAZARDS`), and both facts came out of the guard's
bite, not out of review.

It sees only routes **declared inline in the `routes: [...]` array of `web/src/router.ts`**. A
route added later via `router.addRoute`, imported from another module, or spread in from a variable is
invisible to it, and so is any second router. It also reasons about the **declaration**, never the behaviour:
`beforeEach` could stop honouring `requiresUnlock` entirely and this guard would stay green — that direction is
covered by the router's own tests, not here. And it is one-directional by design: a route that is
`requiresUnlock: true` without `layout: 'app'` is fine and unremarked (a full-screen gated card is a legitimate
shape). Green means exactly: **no inline-declared shell route is missing the unlock gate.**
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ROUTER = ROOT / "web" / "src" / "router.ts"

SHELL_LAYOUT = re.compile(r"layout\s*:\s*'app'")
UNLOCK_GATE = re.compile(r"requiresUnlock\s*:\s*true")
# Best-effort identification for the error message; a route always has one of the two.
PATH_OF = re.compile(r"path\s*:\s*'([^']*)'")
NAME_OF = re.compile(r"name\s*:\s*'([^']*)'")


# Inside a string literal, only these can desynchronise the scanner below (brace matching, comment starts).
# The rest of a string's body is KEPT — blanking it wholesale was the first shape of this function and it made
# the guard report zero shell routes, because `layout: 'app'` is itself a string literal. Caught by its bite.
_STRING_HAZARDS = "{}[]/*"


def strip_noise(src: str) -> str:
    """Blank out comments, and the structural characters inside string literals, preserving offsets.

    Comments are why this guard is object-anchored at all (see the docstring). String bodies keep their text —
    the predicate reads one — but lose any brace, bracket or comment-start that would otherwise desynchronise
    the brace matching. Quote state is tracked rather than regex-replaced: `//` inside a string is not a comment.
    """
    out = list(src)
    i, n = 0, len(src)
    quote: str | None = None
    while i < n:
        ch = src[i]
        if quote:
            if ch == "\\":
                out[i] = " "
                if i + 1 < n:
                    out[i + 1] = " "
                i += 2
                continue
            if ch == quote:
                quote = None
            elif ch in _STRING_HAZARDS:
                out[i] = " "
            i += 1
            continue
        if ch in "'\"`":
            quote = ch
            i += 1
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
            continue
        if ch == "/" and i + 1 < n and src[i + 1] == "*":
            end = src.find("*/", i + 2)
            end = n if end == -1 else end + 2
            for j in range(i, end):
                if src[j] != "\n":
                    out[j] = " "
            i = end
            continue
        i += 1
    return "".join(out)


def route_objects(src: str) -> list[tuple[int, int]] | None:
    """Offsets of every top-level `{...}` inside the `routes: [ ... ]` array, or None if the array is gone."""
    marker = re.search(r"routes\s*:\s*\[", src)
    if not marker:
        return None
    start = marker.end()
    depth, i, n = 1, start, len(src)
    spans: list[tuple[int, int]] = []
    obj_start: int | None = None
    while i < n and depth > 0:
        ch = src[i]
        if ch == "[":
            depth += 1
        elif ch == "]":
            depth -= 1
        elif ch == "{":
            if depth == 1 and obj_start is None:
                obj_start = i
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 1 and obj_start is not None:
                spans.append((obj_start, i + 1))
                obj_start = None
        i += 1
    return spans


def main() -> int:
    if not ROUTER.exists():
        print(f"SHELL ROUTE GATE VALIDATION FAILED:\n  - missing router: {ROUTER}")
        return 1

    raw = ROUTER.read_text(encoding="utf-8")
    src = strip_noise(raw)
    spans = route_objects(src)

    # A missing array is a failure, not a pass: the guard must not go quiet because the thing it watches was
    # restructured out from under it.
    if spans is None:
        print(
            "SHELL ROUTE GATE VALIDATION FAILED:\n"
            "  - no `routes: [` array found in web/src/router.ts. If the route table moved or was built from a "
            "variable, point this guard at the new shape — do not delete the guard."
        )
        return 1

    errors: list[str] = []
    shell_routes = 0
    for start, end in spans:
        body = src[start:end]
        if not SHELL_LAYOUT.search(body):
            continue
        shell_routes += 1
        if UNLOCK_GATE.search(body):
            continue
        # Identified from the RAW slice: `strip_noise` blanks string bodies, so the stripped copy knows a path
        # is there but not what it says.
        original = raw[start:end]
        m = PATH_OF.search(original) or NAME_OF.search(original)
        who = m.group(1) if m else original.strip().splitlines()[0]
        errors.append(
            f"route {who!r}: declares `layout: 'app'` but not `requiresUnlock: true`. A shell route reachable "
            f"on a session alone is the first session-gated-but-not-unlock-gated route in this router — the "
            f"shortcut uc.account.sessions §E3 exists to avoid. If a locked caller genuinely needs an action, "
            f"offer the action (as §E3 does on the sign-in form), not a shell route."
        )

    if errors:
        print("SHELL ROUTE GATE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(f"OK: all {shell_routes} `layout: 'app'` routes in web/src/router.ts declare `requiresUnlock: true`.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
