"""A Kotlin lexer (not a parser) that makes comments and literals unable to pose as structure.

Not a guard — a helper the `validate_*.py` guards import (`scripts/` is on their path as the directory of the script
being run). It lives here so there is exactly one: `validate_coverage` resolves backend refs on masked source, and
`validate_shared_imports_no_features` reads qualified feature names on it, and two lexers would drift.
"""
from __future__ import annotations

import re

_CODE_TOKEN = re.compile(r'//|/\*|"""|["\'`{}]')
_STR_TOKEN = re.compile(r'\\.|\$\{|"|\n', re.S)
_RAW_TOKEN = re.compile(r'\$\{|"""')
_BLOCK_TOKEN = re.compile(r"/\*|\*/")


def _block_comment_end(src: str, start: int) -> int:
    depth = 0  # Kotlin block comments nest
    for m in _BLOCK_TOKEN.finditer(src, start):
        depth += 1 if m.group() == "/*" else -1
        if depth == 0:
            return m.end()
    return len(src)


def mask(src: str) -> str:
    """`src` with every comment and every string/char literal's contents blanked, at unchanged length.

    Newlines and quote characters survive, so offsets and line numbers stay true and a literal still reads as
    "an expression here". What it buys: a `fun`, a `class`, an `@Enabled…` or a brace inside a KDoc, a comment
    or a string can no longer pose as structure — a KDoc showing `fun x() = y` would otherwise resolve as a
    method with a body, and a comment between a gate and its class could otherwise detach the gate. It knows
    comments (nested), `"…"` with escapes and `${…}` templates, raw triple-quoted strings, char literals and
    backticked names — nothing else.
    """
    out = list(src)
    n = len(src)

    def blank(a: int, b: int) -> None:
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    def string_end(i: int, raw: bool) -> tuple[int, int]:
        """From just inside an opening quote: (end of the contents, index past the closing delimiter)."""
        token = _RAW_TOKEN if raw else _STR_TOKEN
        while True:
            m = token.search(src, i)
            if not m:
                return n, n
            t = m.group()
            if t == "${":
                i = code(m.end(), in_template=True) + 1
            elif t.startswith("\\"):
                i = m.end()
            elif t == "\n":  # an unterminated one-line string is malformed source; stop at the line end
                return m.start(), m.start()
            elif raw:  # a raw string closes on the LAST three quotes of a run (`"""a""""` holds `a"`)
                j = m.start()
                while src.startswith('"', j + 3):
                    j += 1
                return j, j + 3
            else:
                return m.start(), m.end()

    def code(i: int, in_template: bool) -> int:
        """Scan code from i; inside a `${…}` template, return the index of the `}` that closes it."""
        depth = 0
        while True:
            m = _CODE_TOKEN.search(src, i)
            if not m:
                return n
            t, s = m.group(), m.start()
            if t == "//":
                e = src.find("\n", s)
                i = n if e == -1 else e
                blank(s, i)
            elif t == "/*":
                i = _block_comment_end(src, s)
                blank(s, i)
            elif t in ('"""', '"'):
                contents_end, i = string_end(m.end(), raw=t == '"""')
                blank(m.end(), contents_end)
            elif t == "'":
                e = s + 1
                while e < n and src[e] not in "'\n":
                    e += 2 if src[e] == "\\" else 1
                blank(s + 1, min(e, n))
                i = e + 1
            elif t == "`":
                e = src.find("`", s + 1)
                i = n if e == -1 else e + 1
            elif t == "{":
                depth += 1
                i = m.end()
            else:  # "}"
                if in_template and depth == 0:
                    return s
                depth -= 1
                i = m.end()

    code(0, in_template=False)
    return "".join(out)
