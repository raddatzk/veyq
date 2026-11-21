#!/usr/bin/env python3
"""Guard that every app draws the core's plans whole (ADR 0154 decision 2, decision 6's structure guard; built by
cr-apps-structure-from-core-plans).

**What it asserts.** A *plan enum* is an enum of the Kotlin core annotated `@Plan`
(`core/…/navigation/Plan.kt`): its entries are a screen's structure — a header's actions, a level's tools, the drawer's
sections, the Settings sections, the sign-in's entries, a row's, a tile's and a link row's menu, New's entries. The
enums are read from that annotation in the core's main sources, never listed here, so an enum annotated tomorrow is
guarded the day it lands; each one's entries come with their serial names (`@SerialName`, else the entry's name), and
its names on each platform are derived: Kotlin's and Swift's the core's (`HeaderAction`, `AppKinds.Create`; Swift's
entries lowerCamel, `openOwnPage`, or as Kotlin/Native mangles a name Objective-C reserves, `theCopy`, `delete_`), Rust's and C#'s the bridge's generated ones (`Bindings.rustNames`: the last segment,
or the last two where two wire types share one — both are accepted; entries the serial name in PascalCase,
`OpenOwnPage`). An `import … as X` of a plan enum in a Kotlin file makes `X` its name there too. Every source is read with
comments and literals blanked (`kotlin_lexer.mask`, `rust_lexer.mask`, the direction guard's `swift_mask` and
`cs_mask`); a rule that reads a literal reads it from the source at the masked text's offsets. Rules:

- **(a) no fallback arm over a plan enum** — a Kotlin `when`, a Swift `switch`, a Rust `match` or a C# `switch`
  (statement or expression) whose arms name an entry of a plan enum takes no `else ->`, `default:` or `_ =>` at its own
  level: an entry added to the enum must fail every drawing that forgot it (Kotlin's `when` and Swift's `switch` over the
  enum are exhaustive without one; Rust's `match` too; C#'s needs `CS8509`/`CS8524` as errors). A Swift `switch` counts as
  over a plan where its case labels name one qualified (`HeaderAction.favorite`), or where every `.x` label it has is an
  entry of one plan enum.
- **(b) no whitelist** — no collection literal (Kotlin's `listOf`/`setOf`/`arrayOf`/… call, a Swift or Rust array
  literal, `vec![…]`, a C# `new …{…}` initializer or `[…]` collection expression) that names an entry of a plan enum, nor
  one of two or more strings that are all serial names of one plan enum (the desktops' `DRAWN` lists were those).
- **(c) no filter or sort over a plan's list** — no `filter`, `filterNot`, `sorted…`, `sortBy…`, `retain`, `Where`,
  `OrderBy…` (and their kin) in the statement that reads a plan's list. A plan's list is a property or function the
  core or the bridge's wire declares as a list of a plan enum or of a wire type that carries one (`HeaderShown.PlanAction`,
  `ShellShown.ShownCreate`), read here from those declarations; its name on each platform is the declared one (Rust:
  snake_case). `<PlanEnum>.entries` filtered counts too.

**Where.** (a) is on for the Kotlin core's main sources (the plans' author: building a plan is filtering, so (b) and (c)
are not the core's) and for the bridge's main sources, which hold all three. Each platform is switched on in its phase of
the CR (`PLATFORMS`), so the guard is never born red: Android's main sources, Apple's `Veyq` and `VeyqShare`, Linux's
`src`, Windows' `Veyq`. No test source is read (a test names entries to assert a plan). `--platform <name>` reads a
platform whose rules are not on yet (a phase's preview); `--paths <file>…` reads exactly the files given, each by its
extension and its platform, whatever is switched on (a bite).

**The defect that bought it.** The audit of 2026-11-20 (ADR 0154 § Context): header actions were strings, every platform
had a fallback arm, and Linux and Windows filtered the core's plan through a whitelist of 13 ids that silently dropped
`banner` and `open-own-page` — a warning in a log is how they went missing unseen.

**Its limit.** It reads names, not types. An arm that names no entry (a `when` over a plan's value through `in set`,
or `.name` compared with a string) is not seen as over a plan; a bare entry is seen in Kotlin only through an explicit
import of the entry or a star import of the enum, and in Swift only as a `.x` label. A plan's list renamed into a
local first (`val b = plan.buttons; b.filter …`) and a list built by a loop with `if` are not seen, nor a plan's list
whose property has a name every collection library uses (`entries`, `keys`, `values`, …: `LIBRARY_NAMES`), read only
as a call. A whitelist of a single string is not seen (it is a comparison as often). Swift's `switch` over a Kotlin
enum has no exhaustiveness of its own (an Objective-C class) and needs a `default:` to compile, which (a) refuses: Apple
switches over each plan enum's generated Swift mirror instead (`src/mobile/apple/generate_plans.py`: `x.entry`, a Swift
enum whose cases are the framework's names, the ones read here; inbox #1050) — a `switch` over the Kotlin class itself
with no `default:` does not compile, so none is left unseen.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from kotlin_lexer import mask as mask_kotlin  # noqa: E402
from rust_lexer import mask as mask_rust  # noqa: E402
from validate_mobile_ui_direction import cs_mask, swift_mask  # noqa: E402

MOBILE = repo_layout.MOBILE
CORE_SRC = MOBILE / "core" / "src"
BRIDGE_SRC = MOBILE / "bridge" / "src"

# Each platform is switched on in its phase of cr-apps-structure-from-core-plans (4 Android, 5 Apple, 6 Linux, 7 Windows).
PLATFORMS: dict[str, bool] = {"android": False, "apple": True, "linux": True, "windows": False}

PLAN_ANNOTATION = re.compile(r"@(?:me\.raddatz\.veyq\.mobile\.navigation\.)?Plan\b(?!\.)")
ENUM_AFTER = re.compile(
    r"(?:\s*@[\w.]+(?:\([^)]*\))?)*\s*(?:(?:public|internal|private)\s+)?enum\s+class\s+([A-Z]\w*)"
)
ENTRY = re.compile(r'(?:@(?:kotlinx\.serialization\.)?SerialName\(\s*"([^"]*)"\s*\)\s*)?\b([A-Z][A-Z0-9_]*)\b\s*(?=[(,;{}]|$)')
CONTAINER = re.compile(r"\b(?:object|class|interface)\s+([A-Z]\w*)[^{;=]*\{")

FILTERS = (
    r"filter|filterNot|filterIsInstance|filterTo|filterNotNull|sorted|sortedBy|sortedByDescending|sortedWith|"
    r"sortedDescending|sort|sortBy|sortWith|sortDescending|retain|retain_mut|filter_map|sort_by|sort_by_key|"
    r"sort_unstable|sort_unstable_by|Where|OrderBy|OrderByDescending|ThenBy|ThenByDescending|RemoveAll"
)


def _rel(p: pathlib.Path) -> str:
    return str(p.relative_to(repo_layout.ROOT)) if p.is_relative_to(repo_layout.ROOT) else str(p)


def _line(text: str, at: int) -> int:
    return text.count("\n", 0, at) + 1


def _close(masked: str, open_at: int, opening: str = "{", closing: str = "}") -> int:
    """The index of the bracket closing the one at `open_at` (or the end)."""
    depth = 0
    for i in range(open_at, len(masked)):
        c = masked[i]
        if c == opening:
            depth += 1
        elif c == closing:
            depth -= 1
            if depth == 0:
                return i
    return len(masked)


def _top(masked: str, start: int, end: int) -> str:
    """`masked[start:end]` with every nested `{…}` (and `(…)`, `[…]`) blanked: the block's own level, offsets kept."""
    out = list(masked[start:end])
    depth = 0
    for k, c in enumerate(out):
        if c in "{([":
            depth += 1
            if depth > 1:
                out[k] = " "
        elif c in "})]":
            if depth > 1:
                out[k] = " "
            depth -= 1
        elif depth > 1 and c != "\n":
            out[k] = " "
    return "".join(out)


def _pascal(s: str) -> str:
    """`Bindings.pascal`: `open-own-page` → `OpenOwnPage`, `UPLOAD_FILES` → `UploadFiles`."""
    parts = [p for p in re.split(r"[._\-]", s) if p]
    return "".join(p.lower().capitalize() if all(not ch.isalpha() or ch.isupper() for ch in p) else p[0].upper() + p[1:] for p in parts)


def _camel(entry: str) -> str:
    """A Kotlin enum entry as Swift reads it through the framework: `OPEN_OWN_PAGE` → `openOwnPage`."""
    p = _pascal(entry)
    return p[0].lower() + p[1:] if p else p


def _snake(s: str) -> str:
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s).lower()


class PlanEnum:
    def __init__(self, name: str, outer: str | None, entries: list[tuple[str, str]], where: str):
        self.name = name
        self.outer = outer
        self.entries = entries  # (Kotlin entry, serial name)
        self.where = where

    @property
    def named(self) -> bool:
        """Its entries carry ids of their own (`@SerialName`): only such an id spelled as a string is the plan's (an
        unnamed entry's name, `MEMORY`, is also a node kind's)."""
        return any(e != s for e, s in self.entries)

    @property
    def kotlin_entries(self) -> set[str]:
        return {e for e, _ in self.entries}

    @property
    def serials(self) -> set[str]:
        return {s for _, s in self.entries}

    @property
    def generated_names(self) -> set[str]:
        """The type's name in the generated Rust and C#: its own, or its outer's and its own joined."""
        return {self.name} | ({self.outer + self.name} if self.outer else set())

    @property
    def generated_entries(self) -> set[str]:
        return {_pascal(s) for s in self.serials}

    @property
    def swift_entries(self) -> set[str]:
        """Each entry as the framework spells it — lowerCamel, and where Objective-C reserves the name Kotlin/Native's
        mangled one (`COPY` → `theCopy`, `DELETE` → `delete_`): both are read, so no list of Kotlin/Native's rules is kept."""
        out: set[str] = set()
        for e in self.kotlin_entries:
            c = _camel(e)
            out |= {c, c + "_", "the" + c[:1].upper() + c[1:]}
        return out


def core_main_sources(src: pathlib.Path) -> list[pathlib.Path]:
    return sorted(p for d in src.iterdir() if d.is_dir() and d.name.endswith("Main") for p in d.rglob("*.kt"))


def plan_enums(core_src: pathlib.Path = CORE_SRC) -> list[PlanEnum]:
    """Every `@Plan` enum of the core's main sources, with its entries and serial names."""
    found: list[PlanEnum] = []
    for path in core_main_sources(core_src):
        src = path.read_text(encoding="utf-8")
        if "Plan" not in src:
            continue
        code = mask_kotlin(src)  # structure: comments and literals blanked
        keep = mask_kotlin(src, literals=False)  # the serial names' literals kept
        for m in PLAN_ANNOTATION.finditer(code):
            e = ENUM_AFTER.match(code, m.end())
            if not e:
                # `@Plan` on something that is no enum class: a mistake the annotation's target cannot refuse
                found.append(PlanEnum("", None, [], f"{_rel(path)}:{_line(code, m.start())}"))
                continue
            name = e.group(1)
            body_open = code.find("{", e.end())
            body_close = _close(code, body_open)
            body = keep[body_open + 1: body_close]
            body_code = code[body_open + 1: body_close]
            semi = body_code.find(";")
            # the entries are the body's first statement, at its own level (an entry's arguments and body blanked)
            entries_code = _top("{" + body_code[: semi if semi >= 0 else len(body_code)] + "}", 0, (semi if semi >= 0 else len(body_code)) + 2)[1:-1]
            entries_text = body[: len(entries_code)]
            entries: list[tuple[str, str]] = []
            for em in ENTRY.finditer(entries_code):
                entry = em.group(2)
                if entry == "SerialName" or entries_code[: em.start(2)].rstrip().endswith("@"):
                    continue
                # its serial name: the `@SerialName("…")` just before it, read where the literals are kept
                before = entries_text[entries_code.rfind(",", 0, em.start(2)) + 1: em.start(2)]
                named = re.search(r'SerialName\(\s*"([^"]*)"\s*\)\s*$', before)
                serial = named.group(1) if named else entry
                if entry not in {x for x, _ in entries}:
                    entries.append((entry, serial))
            outer = None
            for c in CONTAINER.finditer(code, 0, m.start()):
                opening = code.find("{", c.start())
                if opening < m.start() < _close(code, opening):
                    outer = c.group(1)
            found.append(PlanEnum(name, outer, entries, f"{_rel(path)}:{_line(code, m.start())}"))
    return found


def plan_lists(plans: list[PlanEnum], sources: list[pathlib.Path]) -> tuple[set[str], set[str]]:
    """The names of the properties and of the functions that hand a plan's list: declared in the core and the bridge as a
    `List<…>` of a plan enum, or of a class that carries one (a wire type: `PlanAction(val id: HeaderAction, …)`)."""
    types = {p.name for p in plans} | {f"{p.outer}.{p.name}" for p in plans if p.outer}
    qualified = r"(?:[\w.]*\.)?"
    texts = [mask_kotlin(p.read_text(encoding="utf-8")) for p in sources]
    # the wire's carriers: a class whose constructor holds a field of a plan enum
    carriers: set[str] = set()
    plan_type = r"(?:" + "|".join(re.escape(t) for t in sorted(types, key=len, reverse=True)) + r")"
    for t in texts:
        for c in re.finditer(r"\bclass\s+([A-Z]\w*)\s*(?:internal\s+)?(?:constructor\s*)?\(([^)]*)\)", t):
            if re.search(r":\s*" + qualified + plan_type + r"\b\??\s*[,=)]?", c.group(2) + ")"):
                carriers.add(c.group(1))
    element = r"(?:" + "|".join(re.escape(t) for t in sorted(types | carriers, key=len, reverse=True)) + r")"
    listed = r"List<\s*" + qualified + element + r"\s*>"
    props: set[str] = set()
    funs: set[str] = set()
    for t in texts:
        for d in re.finditer(r"\b(?:val|var)\s+(\w+)\s*:\s*" + listed, t):
            props.add(d.group(1))
        for d in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(", t):
            close = _close(t, d.end() - 1, "(", ")")
            if re.match(r"\s*:\s*" + listed, t[close + 1:]):
                funs.add(d.group(1))
    return props, funs


# ---- reading one source ----------------------------------------------------------------------------------------------


class Source:
    def __init__(self, path: pathlib.Path, lang: str):
        self.path = path
        self.lang = lang
        self.raw = path.read_text(encoding="utf-8-sig")
        self.code = {
            "kotlin": mask_kotlin,
            "rust": mask_rust,
            "swift": swift_mask,
            "csharp": cs_mask,
        }[lang](self.raw)

    def at(self, i: int) -> str:
        return f"{_rel(self.path)}:{_line(self.code, i)}"


def _strings(raw: str) -> list[str]:
    return re.findall(r'"((?:[^"\\\n]|\\.)*)"', raw)


class Names:
    """A plan enum's spellings in one source: its type names and its entry names, by language."""

    def __init__(self, plans: list[PlanEnum], src: Source):
        self.by_plan: list[tuple[PlanEnum, set[str], set[str]]] = []
        for p in plans:
            if not p.name:
                continue
            if src.lang == "kotlin":
                types = {p.name} | ({f"{p.outer}.{p.name}"} if p.outer else set())
                for a in re.finditer(r"^\s*import\s+[\w.]*\b" + re.escape(p.name) + r"\s+as\s+(\w+)", src.code, re.MULTILINE):
                    types.add(a.group(1))
                entries = p.kotlin_entries
            elif src.lang == "swift":
                types = {p.name} | ({f"{p.outer}.{p.name}"} if p.outer else set())
                entries = p.swift_entries
            else:
                types = p.generated_names
                entries = p.generated_entries
            self.by_plan.append((p, types, entries))
        self.sep = "::" if src.lang == "rust" else "."
        self._swift = src.lang == "swift"
        # Kotlin's bare entries: an entry imported by name, or every entry of an enum imported with `.*`
        self.bare: dict[str, PlanEnum] = {}
        if src.lang == "kotlin":
            for p, types, entries in self.by_plan:
                for imp in re.finditer(r"^\s*import\s+[\w.]*\b" + re.escape(p.name) + r"\.(\*|[A-Z][A-Z0-9_]*)\s*$", src.code, re.MULTILINE):
                    for e in (entries if imp.group(1) == "*" else {imp.group(1)} & entries):
                        self.bare[e] = p

    def refs(self, text: str) -> list[tuple[PlanEnum, int]]:
        """Every qualified entry of a plan in `text` (and Kotlin's imported bare ones): the plan and the offset."""
        out: list[tuple[PlanEnum, int]] = []
        for p, types, entries in self.by_plan:
            t = "|".join(re.escape(x) for x in sorted(types, key=len, reverse=True))
            e = "|".join(re.escape(x) for x in sorted(entries, key=len, reverse=True))
            if not t or not e:
                continue
            for m in re.finditer(r"(?<![\w.])(?:[\w.]*\.)?(?:" + t + r")" + re.escape(self.sep) + r"(?:" + e + r")\b", text):
                out.append((p, m.start()))
        for entry, p in self.bare.items():
            for m in re.finditer(r"(?<![\w.])" + re.escape(entry) + r"\b(?!\s*\.)", text):
                out.append((p, m.start()))
        return out

    def element_plan(self, elements: list[str]) -> PlanEnum | None:
        """The plan whose entries every element of a collection literal is — qualified, a Kotlin bare one, or a Swift
        `.x` — each perhaps followed by a call on it (`X.A.takeIf { … }`); None where any element is something else (a
        registration's record keyed by an entry, `CreateDialog { create: Create::Pot, … }`, is no whitelist)."""
        call = r"(?:\s*\??\.\s*\w+\s*(?:\(\s*\)|\{\s*\})?)*"
        found: PlanEnum | None = None
        for el in elements:
            hit = None
            for p, types, entries in self.by_plan:
                t = "|".join(re.escape(x) for x in sorted(types, key=len, reverse=True))
                e = "|".join(re.escape(x) for x in sorted(entries, key=len, reverse=True))
                if t and e and re.fullmatch(r"(?:[\w.]*\.|(?:\w+::)*)?(?:" + t + r")" + re.escape(self.sep) + r"(?:" + e + r")" + call, el):
                    hit = p
                    break
                if self.sep == "." and e and re.fullmatch(r"\.(?:" + e + r")" + call, el) and len(elements) >= 2 and el[1:].split(".")[0] in entries:
                    hit = p if self._swift else None
                    if hit:
                        break
            if hit is None and el.split(".")[0] in self.bare:
                hit = self.bare[el.split(".")[0]]
            if hit is None or (found is not None and hit is not found):
                return None
            found = hit
        return found

    def dotted(self, labels: list[str]) -> PlanEnum | None:
        """Swift: the one plan whose entries hold every `.x` of `labels` (at least one), or None."""
        dots = [d for label in labels for d in re.findall(r"(?<![\w)\]])\.([a-z]\w*)\b(?!\s*\()", label)]
        if not dots:
            return None
        for p, _types, entries in self.by_plan:
            if all(d in entries for d in dots):
                return p
        return None


# ---- (a) fallback arms ---------------------------------------------------------------------------------------------

KOTLIN_WHEN = re.compile(r"\bwhen\b\s*(?:\((?:[^()]|\([^()]*\))*\))?\s*\{")
SWIFT_SWITCH = re.compile(r"\bswitch\b[^{\n]*\{")
RUST_MATCH = re.compile(r"\bmatch\b[^{;]*\{")
CS_SWITCH_STMT = re.compile(r"\bswitch\s*\((?:[^()]|\([^()]*\))*\)\s*\{")
CS_SWITCH_EXPR = re.compile(r"\bswitch\s*\{")


def fallback_arms(src: Source, names: Names) -> list[str]:
    code = src.code
    errors: list[str] = []
    if src.lang == "kotlin":
        blocks = [(m, r"(?:^|\n|;)\s*else\s*->", "else ->") for m in KOTLIN_WHEN.finditer(code)]
    elif src.lang == "swift":
        blocks = [(m, r"(?:^|\n|;)\s*default\s*:", "default:") for m in SWIFT_SWITCH.finditer(code)]
    elif src.lang == "rust":
        blocks = [(m, r"(?:^|[,{}\n])\s*_\s*(?:if\b[^=]*)?=>", "_ =>") for m in RUST_MATCH.finditer(code)]
    else:
        blocks = [(m, r"(?:^|[;:{}\n])\s*default\s*:", "default:") for m in CS_SWITCH_STMT.finditer(code)]
        blocks += [(m, r"(?:^|[,{\n])\s*_\s*(?:when\b[^=]*)?=>", "_ =>") for m in CS_SWITCH_EXPR.finditer(code)]
    for m, fallback, said in blocks:
        opening = m.end() - 1
        closing = _close(code, opening)
        top = _top(code, opening, closing + 1)[1:-1]
        plan: PlanEnum | None = None
        refs = names.refs(top)
        if refs:
            plan = refs[0][0]
        elif src.lang == "swift":
            labels = re.findall(r"(?:^|\n)\s*case\b([^:\n]*):", top)
            plan = names.dotted(labels)
        if plan is None:
            continue
        f = re.search(fallback, top)
        if f:
            errors.append(
                f"{src.at(opening + 1 + f.end())}: (a) `{said}` in a {'when' if src.lang == 'kotlin' else 'switch' if src.lang != 'rust' else 'match'} "
                f"over the plan `{plan.name}` — draw every entry; an entry added must fail this drawing"
            )
    return errors


# ---- (b) whitelists ------------------------------------------------------------------------------------------------

KOTLIN_COLLECTION = re.compile(r"\b(?:listOf|listOfNotNull|setOf|arrayOf|mutableListOf|mutableSetOf|hashSetOf|linkedSetOf|enumSetOf|arrayListOf|buildList|buildSet)\s*(?:<[^>()]*>)?\s*\(")
RUST_COLLECTION = re.compile(r"(?:\bvec!\s*\[|(?<![\w)\]?!])\[)")
SWIFT_COLLECTION = re.compile(r"(?<![\w)\]?!])\[")
CS_COLLECTION = re.compile(r"\bnew\b[^;{}()]*?\{|(?<![\w)\]?])\[")


def whitelists(src: Source, names: Names, plans: list[PlanEnum]) -> list[str]:
    code = src.code
    errors: list[str] = []
    if src.lang == "kotlin":
        pattern, opening, closing = KOTLIN_COLLECTION, "(", ")"
    elif src.lang == "rust":
        pattern, opening, closing = RUST_COLLECTION, "[", "]"
    elif src.lang == "swift":
        pattern, opening, closing = SWIFT_COLLECTION, "[", "]"
    else:
        pattern, opening, closing = CS_COLLECTION, None, None
    for m in pattern.finditer(code):
        o = m.end() - 1
        op = opening or code[o]
        cl = closing or ("}" if op == "{" else "]")
        if code[o] != op:
            continue
        c = _close(code, o, op, cl)
        inner = code[o + 1: c]
        if src.lang == "csharp" and op == "[" and re.match(r"\s*[A-Z]\w*\s*(?:\(|\])", inner):
            continue  # an attribute, `[JsonPropertyName(…)]`, or an index
        if src.lang == "csharp" and op == "[" and re.match(r"\s*=(?!=)", code[c + 1:]):
            continue  # an index initializer's key, `[Create.Memory] = …`: a registration, no element
        # the literal's elements at its own level (an element's own call or block blanked)
        flat = _top(op + inner + cl, 0, len(inner) + 2)[1:-1]
        elements = [x.strip() for x in flat.split(",") if x.strip()]
        plan = names.element_plan(elements) if elements else None
        if plan is not None:
            errors.append(f"{src.at(o)}: (b) a collection of the plan `{plan.name}`'s entries — a whitelist; draw the core's list as it is")
            continue
        said = _strings(src.raw[o + 1: c])
        if len(said) >= 2 and re.fullmatch(r"[\s,\"]*", re.sub(r'"(?:[^"\\\n]|\\.)*"', ",", src.raw[o + 1: c]).replace("&", "")):
            for p in plans:
                if p.named and all(s in p.serials for s in said):
                    errors.append(f"{src.at(o)}: (b) a list of the plan `{p.name}`'s ids as strings — a whitelist; draw the core's list as it is")
                    break
    return errors


# ---- (c) filters and sorts over a plan's list -----------------------------------------------------------------------


def statements(code: str, start: int) -> str:
    """From `start` to the end of its statement: the line, and every following line that continues a chain (`.`, `?.`)."""
    end = code.find("\n", start)
    end = len(code) if end < 0 else end
    while True:
        nxt = re.match(r"\n[ \t]*(?:\?\.|\.(?!\.))", code[end:])
        if not nxt:
            break
        e = code.find("\n", end + 1)
        end = len(code) if e < 0 else e
    stop = code.find(";", start, end)
    return code[start: stop if stop >= 0 else end]


def plan_filters(src: Source, props: set[str], funs: set[str], plans: list[PlanEnum]) -> list[str]:
    code = src.code
    errors: list[str] = []
    rust = src.lang == "rust"
    # a property named as every language's own collections name theirs (`map.entries`, `dict.keys`) would read every map
    prop_names = {(_snake(p) if rust else p) for p in props if p not in LIBRARY_NAMES}
    fun_names = {(_snake(f) if rust else f) for f in funs}
    readers: list[tuple[re.Pattern[str], str]] = []
    if prop_names:
        readers.append((re.compile(r"(?<=\.)(" + "|".join(sorted(map(re.escape, prop_names))) + r")\b(?!\s*\()"), "list"))
    if fun_names:
        readers.append((re.compile(r"\b(" + "|".join(sorted(map(re.escape, fun_names))) + r")\s*\("), "list"))
    for p in plans:
        if p.name:
            readers.append((re.compile(r"\b" + re.escape(p.name) + r"\.entries\b"), "entries"))
    seen: set[int] = set()
    for reader, _kind in readers:
        for m in reader.finditer(code):
            stmt = statements(code, m.start())
            f = re.search(r"\.(?:" + FILTERS + r")\b", stmt)
            if f and m.start() not in seen:
                seen.add(m.start())
                errors.append(
                    f"{src.at(m.start())}: (c) `{m.group(1) if m.groups() else m.group(0)}` filtered or sorted (`.{f.group(0)[1:]}`) — "
                    "a plan is drawn whole and in its order; what is offered is the core's"
                )
    return errors


# ---- the sources ---------------------------------------------------------------------------------------------------

# names every language's own collections use: a plan's list so named is read only where it is called (`entries(…)`)
LIBRARY_NAMES = frozenset({"entries", "keys", "values", "items", "first", "count", "size"})

LANG = {".kt": "kotlin", ".swift": "swift", ".rs": "rust", ".cs": "csharp"}


def platform_sources(name: str) -> list[pathlib.Path]:
    if name == "android":
        base = repo_layout.MOBILE_ANDROID / "src"
        return sorted(p for d in base.iterdir() if d.is_dir() and "test" not in d.name.lower() for p in d.rglob("*.kt"))
    if name == "apple":
        return sorted(p for d in ("Veyq", "VeyqShare") for p in (repo_layout.MOBILE_APPLE / d).rglob("*.swift"))
    if name == "linux":
        return sorted((repo_layout.MOBILE_LINUX / "src").rglob("*.rs"))
    if name == "windows":
        return sorted(p for p in (repo_layout.MOBILE_WINDOWS / "Veyq").rglob("*.cs") if not {"bin", "obj"} & set(p.parts))
    raise ValueError(name)


def bridge_sources() -> list[pathlib.Path]:
    return sorted(p for d in BRIDGE_SRC.iterdir() if d.is_dir() and d.name.endswith("Main") for p in d.rglob("*.kt"))


def check_files(files: list[pathlib.Path], rules: str, plans: list[PlanEnum], props: set[str], funs: set[str]) -> list[str]:
    errors: list[str] = []
    for path in files:
        lang = LANG.get(path.suffix)
        if lang is None:
            continue
        src = Source(path, lang)
        names = Names(plans, src)
        if "a" in rules:
            errors += fallback_arms(src, names)
        if "b" in rules:
            errors += whitelists(src, names, plans)
        if "c" in rules:
            errors += plan_filters(src, props, funs, plans)
    return errors


def check(platforms: dict[str, bool] | None = None, paths: list[pathlib.Path] | None = None) -> tuple[list[str], int]:
    plans = plan_enums()
    errors = [f"{p.where}: `@Plan` on no enum class — the annotation marks a plan's enum" for p in plans if not p.name]
    plans = [p for p in plans if p.name]
    if not plans:
        return [f"{_rel(CORE_SRC)}: no `@Plan` enum read — the guard would check nothing"], 0
    for p in plans:
        if not p.entries:
            errors.append(f"{p.where}: the plan `{p.name}` has no entry read")
    props, funs = plan_lists(plans, core_main_sources(CORE_SRC) + bridge_sources())
    if paths is not None:
        return errors + check_files(paths, "abc", plans, props, funs), len(paths)
    core = core_main_sources(CORE_SRC)
    bridge = bridge_sources()
    errors += check_files(core, "a", plans, props, funs)
    errors += check_files(bridge, "abc", plans, props, funs)
    read = len(core) + len(bridge)
    for name, on in (PLATFORMS if platforms is None else platforms).items():
        if on:
            files = platform_sources(name)
            errors += check_files(files, "abc", plans, props, funs)
            read += len(files)
    return errors, read


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--platform", action="append", choices=sorted(PLATFORMS), default=[], help="read a platform not switched on yet")
    ap.add_argument("--paths", nargs="+", type=pathlib.Path, help="read exactly these files (a bite)")
    args = ap.parse_args(argv)
    on = dict(PLATFORMS)
    for p in args.platform:
        on[p] = True
    errors, read = check(on, [p.resolve() for p in args.paths] if args.paths else None)
    for e in errors:
        print(e)
    if errors:
        print(f"validate_mobile_structure: {len(errors)} violation(s)")
        return 1
    plans = [p.name for p in plan_enums() if p.name]
    if args.paths:
        print(f"validate_mobile_structure: OK ({len(plans)} plan enums, the {read} file(s) given)")
        return 0
    shown = ", ".join(n for n, v in on.items() if v) or "none"
    print(f"validate_mobile_structure: OK ({len(plans)} plan enums, {read} files; the core and the bridge, platforms on: {shown})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
