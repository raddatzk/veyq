"""A Rust lexer (not a parser) that makes comments and literals unable to pose as structure — `kotlin_lexer`'s
counterpart for the Linux app's sources, which that one cannot read: a Rust lifetime (`'a`) is no char literal, and a
raw string (`r#"…"#`) closes on its own hashes.

`mask(src)` blanks every comment (`//…`, nested `/* … */`) and every literal's contents (`"…"` with escapes, `b"…"`,
raw `r"…"` / `r#"…"#`, char literals `'x'` / `'\\n'` / `'\\u{…}'`) at unchanged length: newlines and quote characters
survive, so offsets and line numbers stay true and a literal still reads as one. `literals=False` blanks the comments
only and keeps every literal's contents, for a rule about what a literal says.
"""
from __future__ import annotations

import re

_CHAR = re.compile(r"'(?:\\(?:u\{[0-9a-fA-F_]+\}|x[0-9a-fA-F]{2}|.)|[^\\'\n])'")
_RAW_OPEN = re.compile(r'(?<![\w])b?r(#*)"')


def mask(src: str, literals: bool = True) -> str:
    out = list(src)

    def blank(a: int, b: int) -> None:
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            end = src.find("\n", i)
            end = n if end < 0 else end
            blank(i, end)
            i = end
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif (m := _RAW_OPEN.match(src, i)) is not None:
            close = '"' + m.group(1)
            end = src.find(close, m.end())
            end = n if end < 0 else end
            if literals:
                blank(m.end(), end)
            i = end + len(close)
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == "\\" else 1
            if literals:
                blank(i + 1, min(j, n))
            i = j + 1
        elif c == "'":
            m = _CHAR.match(src, i)
            if m is None:  # a lifetime or a label: code
                i += 1
            else:
                if literals:
                    blank(i + 1, m.end() - 1)
                i = m.end()
        else:
            i += 1
    return "".join(out)
