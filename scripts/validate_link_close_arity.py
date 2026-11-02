#!/usr/bin/env python3
"""Every write that closes a link sits in the one close body, which marks the subtree below it (ADR 0114 decisions 1, 8).

**What it asserts.** A link is open while it is enabled, not deleted and before its end date; each transition to closed
must go through `SharingStore.close` (`me.raddatz.veyq.core.sharing.SharingStore`), which clears the link's keys and,
in the same transaction, marks every node of its root's subtree due for a re-key — or through the tombstone
(`SharingStore.tombstone`) that `close` calls for a delete and the node purge calls for links with nothing left below
them. The subject is derived from the sources on every run, never listed: every backend main Kotlin file
(`backend_tree.main_kotlin()`) that names `ShareEntity` or the table `veyq_share`, read with comments masked
(`kotlin_lexer`). In it, a **closing write** is

  - in code: an assignment to `enabled` whose value is not the literal `true`, or to `deleted` or `expiryClosed` (the
    end-date sweep's record) whose value is not the literal `false` — a receiver's (`share.enabled = …`) or a bare one
    (`apply { enabled = … }`); a `val`/`var` declaration is none;
  - in a string literal that is a write — the first argument of an `.update(` call (Panache's bulk update) or a
    statement starting `UPDATE` (HQL or native SQL) — the same three columns set, by literal (`enabled = false`,
    `deleted = true`, `expiry_closed = TRUE`, case-insensitively, `0`/`1` too) or by parameter (`enabled = ?1`,
    `= :x`), before the statement's `where`.

Every closing write must lie inside the body of `fun close(` or `fun tombstone(` in `SharingStore`. Red on one
anywhere else; on a missing `SharingStore`, `close` or `tombstone`; on a `close` that calls neither the tombstone nor a
function that marks nodes due (an assignment `rekeyDue = true`); on a body in which the guard finds none of the closing
writes it exists for (a pattern that no longer sees the real spelling would make every stray write invisible too); and on
an empty subject.

**The defect that bought it.** Before ADR 0114 five places closed a link and none re-keyed anything: a revoke wrote
`enabled = false` and nothing else, a re-issue spent the old row with its own `update("deleted = true …")`, the bulk
switch-off and a removed member's switch-off were each a bulk `update("enabled = false …")`, and an end date closed a
link by the clock alone. A former holder of the link with a database dump read everything added under its root after
the close. One body that marks, and a guard that no second spelling can bypass it unseen, are what keep a sixth from
appearing quietly.

**Its limits.** It reads text, not types: an assignment to another entity's `enabled` in a file that also names
`ShareEntity` is red too (rename it or move it — no such file exists today), and a write through a receiver of another
name in a file that names neither `ShareEntity` nor `veyq_share` (a native statement on a table name built at runtime, a
column spelled through a constant) is not seen. A named argument `enabled = false` in such a file counts as a write. It
proves where the writes are, never which rows the close reaches or that the marks land: the subtree marked after each
kind of close, a `WRITE_ONLY` close marking nothing and the sweep marking a link once are the slice tests'
(`LinkCloseRekeyCoreSliceTest`).
"""
from __future__ import annotations

import re
import sys

import backend_tree
import kotlin_lexer

STORE = "me.raddatz.veyq.core.sharing.SharingStore"
BODIES = ("close", "tombstone")
NAMES = re.compile(r"\bShareEntity\b|\bveyq_share\b")
# In code: an assignment (not a comparison) to one of the three columns, with the value it assigns.
CODE_WRITE = re.compile(r"(?<![\w$])(?:[\w)\]]+\s*\.\s*)?(enabled|deleted|expiryClosed)\s*=(?!=)\s*([^\s,;)}]+)")
DECLARATION = re.compile(r"\b(?:val|var)\s+$")
# The value that leaves a link as open as it was: re-enabling, undeleting is never written, clearing the sweep's mark.
HARMLESS = {"enabled": "true", "deleted": "false", "expiryClosed": "false"}
LITERAL = re.compile(r'"""[ \n]*"""|"[ ]*"')
LITERAL_WRITE = re.compile(
    r"\b(?:\w+\.)?(?:enabled\s*=\s*(?:false|0\b|[?:]\w*)|deleted\s*=\s*(?:true|1\b|[?:]\w*)|"
    r"expiry_?closed\s*=\s*(?:true|1\b|[?:]\w*))",
    re.IGNORECASE,
)
WHERE = re.compile(r"\bwhere\b", re.IGNORECASE)
UPDATE_CALL = re.compile(r"\.update\(\s*$")
UPDATE_STATEMENT = re.compile(r"^\s*update\b", re.IGNORECASE)


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def body_span(code: str, name: str) -> tuple[int, int] | None:
    """The `{…}` body of `fun <name>(` in comment- and literal-masked [code], or None."""
    m = re.search(rf"\bfun\s+{name}\s*\(", code)
    if not m:
        return None
    depth, i = 1, m.end()
    while i < len(code) and depth:  # the parameter list
        depth += {"(": 1, ")": -1}.get(code[i], 0)
        i += 1
    start = code.find("{", i)
    if start == -1 or code[i:start].strip().startswith("="):
        return None
    depth, j = 0, start
    while j < len(code):
        depth += {"{": 1, "}": -1}.get(code[j], 0)
        j += 1
        if depth == 0:
            return start, j
    return None


def in_parentheses(code: str, pos: int) -> bool:
    """Whether the innermost bracket open at [pos] is a `(` — a named argument there, never a statement (a lambda's
    `{` inside a call opens a block again)."""
    stack: list[str] = []
    for ch in code[:pos]:
        if ch in "({":
            stack.append(ch)
        elif ch in ")}" and stack:
            stack.pop()
    return bool(stack) and stack[-1] == "("


def closing_writes(src: str) -> list[tuple[int, str]]:
    """Each closing write of [src] as (offset, text) — in code and in write statements (module docstring)."""
    code = kotlin_lexer.mask(src)
    lits = kotlin_lexer.mask(src, literals=False)
    found: list[tuple[int, str]] = []
    for m in CODE_WRITE.finditer(code):
        column, value = m.group(1), m.group(2)
        if DECLARATION.search(code[max(0, m.start() - 12):m.start()]):
            continue
        if "." not in code[m.start():m.start(1)] and in_parentheses(code, m.start()):
            continue  # `ShareSummary(enabled = s.enabled)`: a named argument, no write
        if value != HARMLESS[column]:
            found.append((m.start(), m.group().strip()))
    for m in LITERAL.finditer(code):
        quote = 3 if m.group().startswith('"""') else 1
        a, b = m.start() + quote, m.end() - quote
        text = lits[a:b]
        if not (UPDATE_CALL.search(code[max(0, m.start() - 40):m.start()]) or UPDATE_STATEMENT.search(text)):
            continue
        where = WHERE.search(text)
        head = text[: where.start()] if where else text
        for w in LITERAL_WRITE.finditer(head):
            found.append((a + w.start(), w.group().strip()))
    return found


def main() -> int:
    errors: list[str] = []
    store = backend_tree.source_file(STORE)
    if not store.is_file():
        print(f"LINK CLOSE ARITY VALIDATION FAILED:\n  - `{STORE}` is missing — re-point this guard, never skip it.")
        return 1
    store_src = store.read_text(encoding="utf-8")
    store_code = kotlin_lexer.mask(store_src)
    spans: dict[str, tuple[int, int]] = {}
    for name in BODIES:
        span = body_span(store_code, name)
        if span is None:
            errors.append(f"`SharingStore.{name}` (a block body) is not found — the one close body moved or was renamed.")
        else:
            spans[name] = span
    if "close" in spans:
        a, b = spans["close"]
        body = store_code[a:b]
        marker = next((n for n in re.findall(r"\b(\w+)\s*\(", body) if n != "close"
                       and (s := body_span(store_code, n)) and re.search(r"\brekeyDue\s*=\s*true\b", store_src[s[0]:s[1]])), None)
        if marker is None:
            errors.append("`SharingStore.close` calls no function that marks nodes due (`rekeyDue = true`): a close would "
                          "leave the subtree below the link under keys its holder has (ADR 0114 decision 4).")
        if not re.search(r"\btombstone\s*\(", body):
            errors.append("`SharingStore.close` does not call `tombstone`: a delete would bypass the one close body.")

    subject = 0
    inside = {name: 0 for name in BODIES}
    for path in backend_tree.main_kotlin():
        src = path.read_text(encoding="utf-8")
        if not NAMES.search(kotlin_lexer.mask(src, literals=False)):
            continue
        subject += 1
        for pos, text in closing_writes(src):
            if path == store:
                owner = next((n for n, (a, b) in spans.items() if a <= pos < b), None)
                if owner:
                    inside[owner] += 1
                    continue
            rel = path.relative_to(backend_tree.ROOT)
            errors.append(
                f"{rel}:{line_of(src, pos)}: `{text}` closes a link outside `SharingStore.close` — a close there marks "
                f"nothing below the link, so its holder reads what is added after it with a dump (ADR 0114 decision 1). "
                f"Close it through `SharingStore.close` (under the scope row's lock, then the share row's)."
            )
    if subject == 0:
        errors.append("no backend main Kotlin file names `ShareEntity` — the subject is empty; an empty set is vacuously green.")
    for name, count in inside.items():
        if name in spans and count == 0:
            errors.append(f"no closing write was found inside `SharingStore.{name}` — the guard's patterns no longer see the "
                          f"real spelling, which would hide a stray one too.")
    if errors:
        print("LINK CLOSE ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {subject} file(s) naming a link read; every closing write ({sum(inside.values())}) sits in "
          f"SharingStore.close / tombstone")
    return 0


if __name__ == "__main__":
    sys.exit(main())
