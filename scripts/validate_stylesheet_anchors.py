#!/usr/bin/env python3
"""Guard that every stylesheet rule is written for an element the markup can actually produce.

**What it asserts.** For every package that has a `src/styles/` directory (today `src/web/` and `src/admin-web/`,
found by walking the directory the code surfaces live in — a third one is in scope the day it lands), every
**anchor compound** in those sheets plus the package's `src/style.css` — and every other `.css` file under its `src/`, which is where an
application's own sheet lives (`src/web/src/features/memories/memories.css`, inbox #513; a sheet that moves out of
`src/styles/` must not move out of this guard) — names at least one class that the package's own markup
applies. The anchor compound is a selector's first compound, the part before any descendant/child/sibling
combinator: in `.memory-card .veyq-card-frame__body` it is `.memory-card`, in `.veyq-danger-action.veyq-button` it
is both classes together. It is the element the rule is *written for*. A rule whose anchor names only classes
no markup ever applies cannot match anything, in any state, on any surface.

**Why the ANCHOR and not every class in the selector** — this is the whole design, and it is what makes the
guard possible at all. A sheet may legitimately reach *into* a dependency's DOM and compound with it — the clients'
sheets did so with every component of the library they used until ADR 0107 (`.memory-card .p-card-body`), and a map or
PDF library renders classes of its own — and such vendor class names are applied by the dependency at runtime, never
by our markup. Judging every class would
therefore need a list of the dependency's class names — and the only honest source for that list is
`node_modules`, which **the trust-chain job does not have**: `.github/workflows/trust-chain.yml` runs
`checkout` + a venv + `pip install pyyaml` and nothing else, on purpose, because the whole run is seconds.
A predicate harvesting vendor names would be green on a developer's machine and red in CI — the exact
failure inbox #140 refused, displaced one step. Anchoring removes the question instead of answering it:
**no rule in either package anchors on vendor classes alone**, because a vendor override is always scoped
by a project class. Measured 2026-09-21, with that component library still in place — 0 vendor-only anchors out of
564 (web) and 70 (admin-web), against 2083 and 1951 vendor class names harvested from `node_modules` purely to check
this claim. Re-run it by
intersecting each anchor compound with the names in `node_modules`; it is the one assumption that, if it
ever stopped holding, would start costing this guard false positives rather than misses. So there is no
vendor list here, and none is needed.

**The defect that bought it.** inbox #140. `cr-sidebar-favorites-show-their-cover` found the Memory-cover
styling for the sidebar's Favorites section fully written and never wired: `layout.css` carried
`.sidebar-favorite-cover …` plus three `.mobile-nav-favorite` rules with **zero** markup users. The code
compiled, every test passed, and the only signal that a shipped feature was missing a surface was CSS
nobody had ever matched. That row deliberately shipped **no** guard, and the reason was not the exception
list — it was the base rate: `src/web/src/styles/` was then 71 % dead (461 of 648 classes), so any such
predicate was red on the day it was born, which `changes/README.md` § *Run a new guard's predicate against
the UNTOUCHED tree before you write it down* forbids outright. inbox #146 deleted that corpus (11209 → 3676
lines across both packages), which is what made this guard green on arrival and therefore writable.

**Its limit.** Six, and the first two are the sharp ones.

  (a) **It judges the anchor, and nothing deeper.** A dead class *inside* a selector — the `.dead` of
      `.alive .dead` — is invisible to it. That is deliberate, not an oversight: the inside of a selector is
      exactly where the vendor reach-in lives, and buildless there is no way to tell a dependency's class
      from a typo. So green means every rule *can* match; it does not mean every part of every selector can.
  (b) **"Applied by the markup" is read as broadly as possible, on purpose.** The vocabulary is class
      attributes, plus every string literal and object key in script, plus CSS-selector strings, plus the
      static prefix of a template literal in a class context. A class that merely shares its spelling with
      an unrelated string keeps its rule alive. The bias is deliberate: this guard's false answers must be
      *misses*, never accusations, because a guard that cries wolf over live CSS gets switched off. A sweep
      wanting to *delete* dead CSS needs the narrower reading and a live pass — see #146 for what that took.
      **One narrowing, and only one: test evidence is not markup.** Tests (`*.test.*`, `*.spec.*`), the
      helpers under `src/test/`, snapshots, fixtures and goldens are left out of the vocabulary, because a test
      names a class to *assert* on it — inbox #362's case was a test asserting that a button does NOT carry
      `memory-action-collapsible`, which kept a hiding rule "anchored" after its last rendering user was gone.
      Leaving them out cannot accuse live CSS: a class only a test spells is a class no rendered file spells.
  (c) **A class composed at runtime is seen only by its static prefix** (`` :class="`is-${status}`" `` keeps
      every `is-*` alive). Anything assembled from fragments with no literal prefix is not seen at all.
  (d) **It proves a rule CAN match, never that anything renders it.** A component that exists and is never
      routed to keeps all of its CSS alive here. Reachability is a different question and no static check
      answers it.
  (e) **It does not look at `<style scoped>` blocks in `.vue` files.** A class defined in a scoped block and
      unused in its own file is a separate, fully-derived predicate that inbox #140 records as its own item.
  (f) **Two wrong shapes were reached for first, and they are recorded here because the next reader will
      reach for the same two.** *Harvesting vendor names from `node_modules`* — the shape the #146 sweep
      actually used — works on a developer's machine and cannot work in the buildless job, as above.
      *Scoping by class-name prefixes derived from the markup* (judge a class only if some rendered class
      shares its first hyphen-segment) looks equivalent and is not: measured, it let the bare prefix `p` in
      — from `:class="{ 'is-active': themePreference === p }"`, where `p` is a loop variable, and from a
      `p-btn` string in a test — which pulled all 36 overrides of the then component library into scope and made the predicate
      red on arrival with nothing wrong.

Its own source is not in what it scans (the corpus is the two packages' `src/`), so the usual trap — a
guard matching, inside its own documentation, the literal it forbids — does not apply here.
"""
from __future__ import annotations

import pathlib
import re
import sys

import repo_layout  # where the code surfaces live (ADR 0106)

ROOT = pathlib.Path(__file__).resolve().parent.parent

# A CSS class token, and the combinators that end a selector's first compound.
CLASS_IN_SELECTOR = re.compile(r"\.(-?[_a-zA-Z][_a-zA-Z0-9-]*)")
COMBINATOR = re.compile(r"[\s>+~]")
COMMENT = re.compile(r"/\*.*?\*/", re.S)

# Markup side. CLASS_ATTR is the literal `class="…"`; STRING_LIT is every quoted run in a script;
# OBJ_KEY is a bare object key; CLASS_CTX is where a template literal may compose a class name.
CLASS_ATTR = re.compile(r"""\bclass\s*=\s*["']([^"']*)["']""")
STRING_LIT = re.compile(r"'([^'\\\n]*(?:\\.[^'\\\n]*)*)'|\"([^\"\\\n]*(?:\\.[^\"\\\n]*)*)\"")
TEMPLATE_LIT = re.compile(r"`([^`\\]*(?:\\.[^`\\]*)*)`", re.S)
OBJ_KEY = re.compile(r"([A-Za-z_$][\w$-]*)\s*:")
CLASS_CTX = re.compile(
    r"""(?::class|v-bind:class)\s*=\s*"([^"]*)"|"""
    r"""(?::class|v-bind:class)\s*=\s*'([^']*)'|"""
    r"""classList\.(?:add|remove|toggle|contains)\(([^)]*)\)|"""
    r"""\.className\s*=\s*([^\n]*)""",
    re.S,
)
# A string literal's body nests: `:class="{ 'is-open': x }"` and `:pt="{ panel: { class: 'y' } }"` both
# carry the real class one quoting level down, so splitting on whitespace alone leaves "'is-open':" as a
# single token that matches nothing.
SPLIT_NESTED = re.compile(r"""[\s,:{}\[\]()'"`|&?+!]+""")
CLASS_TOKEN = re.compile(r"^-?[_a-zA-Z][_a-zA-Z0-9-]*$")

MARKUP_SUFFIXES = (".vue", ".ts", ".tsx", ".js", ".html")

# Test evidence is not markup (limit (b)'s one narrowing): a test names a class to assert on it — often that it
# is ABSENT — and a test helper, fixture or golden snapshot renders nothing the browser is ever served.
TEST_FILE = re.compile(r"\.(test|spec)\.[^.]+$|\.golden\b")
TEST_DIRS = {"test", "__tests__", "__snapshots__", "__fixtures__", "__mocks__"}


def is_test_evidence(path: pathlib.Path, src: pathlib.Path) -> bool:
    """A test, a test helper (`src/test/`), a snapshot, a fixture or a golden — never rendered markup."""
    return bool(TEST_FILE.search(path.name)) or bool(TEST_DIRS & set(path.relative_to(src).parts[:-1]))


def rel(path: pathlib.Path) -> str:
    """Repo-relative, because an absolute path is unreadable in CI output."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def packages() -> list[pathlib.Path]:
    """Every package that has a `src/styles/` directory — derived, never listed: the directories where the code
    surfaces live (`repo_layout.SRC`)."""
    return sorted(p for p in repo_layout.SRC.iterdir() if (p / "src" / "styles").is_dir())


def sheets(pkg: pathlib.Path) -> tuple[list[pathlib.Path], list[pathlib.Path]]:
    """(the sheets under src/styles/, the barrel src/style.css if present + every other `.css` under src/) — the
    first kept apart on purpose.

    Merging them hides an emptied `src/styles/`: the barrel alone keeps the combined list non-empty, the
    guard validates one file instead of nine and still reports green. Its own bite test caught exactly that.
    The others are found by walking `src/`, never listed: an application's sheet (inbox #513) is judged the day it
    lands, wherever under `src/` it sits.
    """
    styles = pkg / "src" / "styles"
    inner = sorted(styles.glob("*.css"))
    barrel = pkg / "src" / "style.css"
    others = sorted(p for p in (pkg / "src").rglob("*.css") if p.parent != styles and p != barrel)
    return inner, ([barrel] if barrel.exists() else []) + others


def rule_heads(css: str):
    """Yield (selector_list, line) for every qualified rule, at any nesting depth.

    A tolerant hand parser rather than a dependency: the trust-chain job installs only PyYAML. It tracks
    comments, quoted strings, at-statements and at-blocks, and recurses into rule bodies because
    `dark.css` is one `.veyq-dark { … }` using native CSS nesting.
    """
    i, n, line = 0, len(css), 1
    buf_start, head_line = 0, 1
    while i < n:
        ch = css[i]
        if css.startswith("/*", i):
            j = css.find("*/", i + 2)
            chunk = css[i : n if j < 0 else j + 2]
            line += chunk.count("\n")
            i = n if j < 0 else j + 2
            continue
        if ch in "\"'":
            quote, i = ch, i + 1
            while i < n and css[i] != quote:
                i += 2 if css[i] == "\\" else 1
            i += 1
            continue
        if ch == ";":
            # An at-STATEMENT (`@import …;`) ends here. Without this the imports stay in the buffer, the
            # next rule head reads as one starting with '@' and is skipped -- which is how the dead
            # `.veyq-shell` of inbox #136 stayed invisible to #146's first predicate.
            i += 1
            buf_start, head_line = i, line
            continue
        if ch == "{":
            head = COMMENT.sub(" ", css[buf_start:i]).strip()
            if head and not head.startswith("@"):
                yield head, head_line
            i += 1
            buf_start, head_line = i, line
            continue
        if ch == "}":
            i += 1
            buf_start, head_line = i, line
            continue
        if ch == "\n":
            line += 1
            if not css[buf_start:i].strip():
                head_line = line
        i += 1


def split_selectors(head: str) -> list[str]:
    """Split a selector list on top-level commas (parenthesised commas, e.g. :is(), stay put)."""
    out, depth, cur = [], 0, []
    for ch in head:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    out.append("".join(cur))
    return [s.strip() for s in out if s.strip()]


def anchor_classes(selector: str) -> list[str]:
    """The classes of the selector's first compound — the element the rule is written for."""
    first = COMBINATOR.split(selector.strip(), 1)[0]
    return CLASS_IN_SELECTOR.findall(first)


def markup_vocabulary(pkg: pathlib.Path):
    """Every class name the package's markup might apply, read as broadly as possible (limit (b)).

    Returns (names, prefixes); `prefixes` are the static heads of template literals in a class context,
    so `` :class="`is-${status}`" `` keeps every `is-*` alive.
    """
    src = pkg / "src"
    names: set[str] = set()
    prefixes: set[str] = set()
    for path in src.rglob("*"):
        if path.suffix not in MARKUP_SUFFIXES:
            continue
        if "styles" in path.relative_to(src).parts or is_test_evidence(path, src):
            continue
        text = path.read_text(errors="ignore")
        for match in CLASS_ATTR.finditer(text):
            for token in re.split(r"[\s,]+", match.group(1)):
                if CLASS_TOKEN.match(token):
                    names.add(token)
        for match in STRING_LIT.finditer(text):
            raw = match.group(1) if match.group(1) is not None else match.group(2)
            for token in SPLIT_NESTED.split(raw):
                if CLASS_TOKEN.match(token):
                    names.add(token)
                elif token.startswith(".") and CLASS_TOKEN.match(token[1:]):
                    # A selector string names the class it looks for: querySelector('.x'), find('.x').
                    names.add(token[1:])
        for match in OBJ_KEY.finditer(text):
            if CLASS_TOKEN.match(match.group(1)):
                names.add(match.group(1))
        for match in CLASS_CTX.finditer(text):
            expr = next(g for g in match.groups() if g is not None)
            for literal in TEMPLATE_LIT.finditer(expr):
                for prefix in re.findall(r"([-_a-zA-Z][\w-]*)\$\{", literal.group(1)):
                    prefixes.add(prefix)
    return names, prefixes


def alive(name: str, names: set, prefixes: set) -> bool:
    return name in names or any(name.startswith(p) for p in prefixes)


def main() -> int:
    errors: list[str] = []
    checked = 0

    pkgs = packages()
    if not pkgs:
        print("STYLESHEET ANCHOR VALIDATION FAILED:")
        print("  - no package with a src/styles/ directory — this guard's corpus is stale")
        return 1

    for pkg in pkgs:
        inner, barrel = sheets(pkg)
        if not inner:
            errors.append(f"{rel(pkg)}: src/styles/ has no .css file — corpus stale, not a pass")
            continue
        css_files = inner + barrel
        names, prefixes = markup_vocabulary(pkg)
        if not names:
            errors.append(f"{rel(pkg)}: no class name found in any markup — corpus stale, not a pass")
            continue
        for css_file in css_files:
            for head, line in rule_heads(css_file.read_text()):
                for selector in split_selectors(head):
                    anchor = anchor_classes(selector)
                    if not anchor:
                        continue  # element/pseudo anchor (`body`, `:root`) — nothing to judge
                    checked += 1
                    if not any(alive(c, names, prefixes) for c in anchor):
                        errors.append(
                            f"{rel(css_file)}:{line}: `{selector}` is anchored on "
                            + ", ".join("." + c for c in anchor)
                            + " — no markup in "
                            + f"{rel(pkg / 'src')} applies any of them, so this rule cannot match"
                        )

    if not checked and not errors:
        errors.append("no anchor compound was judged at all — this guard validated nothing")

    if errors:
        print("STYLESHEET ANCHOR VALIDATION FAILED:")
        for err in errors:
            print(f"  - {err}")
        print(
            "\nA rule nothing can match is the signal inbox #140 was written about: a shipped feature "
            "missing its surface looks exactly like this, and nothing else reports it."
        )
        return 1

    print(
        f"stylesheet anchors OK: {checked} anchor compound(s) across "
        f"{len(pkgs)} package(s) each name a class the markup applies"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
