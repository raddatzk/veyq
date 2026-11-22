#!/usr/bin/env python3
"""Guard that each platform's UI depends application → core UI, never back and never application → application
(ADR 0154 decision 3, the apps' half of ADR 0079; built by cr-apps-core-ui-layer). The Kotlin core's own direction —
the platform, one module per application, the composition — is the build's, held by
`validate_mobile_module_graph.py` (ADR 0155 decision 7.1), which retired this guard's rules (c1)–(c3) over the core.

**What it asserts.** The applications are not listed here: they are derived from the entries of the Kotlin core's
`ApplicationId` (`core/…/navigation/Applications.kt`, `MEMORIES("memories")` names the package `…mobile.memories`), so
an application added there is guarded the day it lands. Every source is read with comments and literals blanked
(`kotlin_lexer.mask`, `rust_lexer.mask`, `cs_mask`), so a name in a comment is no reference. Rules:

- **(a) and (b) a platform's UI**, switched on per platform in its phase of cr-apps-core-ui-layer (`PLATFORMS`), so
  the guard is never born red. Android: the sources of every source set but a test one, under
  `src/mobile/android/src/<set>/kotlin/me/raddatz/veyq/mobile/android/`; an application's UI is the package
  `…android.apps.<application>` (directory `apps/<application>/`), everything else is the core UI:
  - **(a)** no core UI file names `…android.apps` — but the one composition root, `main/…/android/Applications.kt`,
    which registers each application into the core UI's registry (the web's `src/web/src/applications.ts`).
  - **(b)** no file of one application's UI names another application's UI (`…android.apps.<other>`), nor another core
    application's package (`…mobile.<other>`, its generated API `…mobile.api.<other>`).
  - **(c)** no core UI file names a core application's package (`me.raddatz.veyq.mobile.<application>`, the module
    `:apps:<application>`, or its generated API `…mobile.api.<application>`) — but the composition root: what the core UI
    holds of an application (its pages' models, its uploads) the application holds itself, in the `Holding` it registers,
    and the core UI asks every holding (inbox #1056). Each platform has its (c), below (inbox #1066).
  - fail closed: a directory under `apps/` that is no application, an application without a file under `apps/`,
    a composition root that is missing or does not name every application's package, and a `package` that is not
    its directory's are each red. Kotlin's packages make the import the whole edge: a symbol of another package is
    unreachable without its name spelled.
  Apple: the Swift sources of `src/mobile/apple/Veyq/` and the share extension's `src/mobile/apple/VeyqShare/`; an
  application's UI is the group `Veyq/Apps/<Application>/` (the id capitalized, `memories` → `Memories`), everything
  else the core UI. Swift is one module without imports, so a reference is a name: the guard indexes what each
  application declares at its files' top level and is not `private`/`fileprivate` (a type, a function, a global) and
  the members its top-level `extension`s add (a `.member` reference), and reads every other file for them, its own
  declarations aside:
  - **(a)** no core UI file names an application's declaration — but the composition root, `Veyq/Applications.swift`.
  - **(b)** no application names another's, nor another core application's Kotlin export.
  - **(c)** no core UI file names a core application's Kotlin export through the framework: what each module under
    `src/mobile/apps/<application>/` hands Objective-C (`kotlin_exports`: its non-private, non-internal top-level classes,
    interfaces, objects and type aliases and each file's `…Kt` facade, and its top-level extensions as a `.member(` call —
    `AppleSessions.openReceipt`), less what the platform or the composition declares under the same name; one marked
    `@HiddenFromObjC` or `@PlatformApi` is none. What the core UI held of an application lives in the `Holding` the
    application registers (`Core/Holding.swift`), and what it asked of an application's Apple object for itself
    (watching a state, a picked file's source) is the composition's `ApplePlatform`.
  - fail closed: a directory under `Apps/` that is no application, an application without a file, a composition root
    that is missing or names nothing of an application, and a top-level name declared by two sides (an overload of a
    core function in an application, which the index cannot tell from core's) are each red.

  Linux: the crate's sources under `src/mobile/linux/src/` (read with comments and literals blanked, `rust_lexer.mask`);
  an application's UI is the module `apps::<application>` (directory `src/apps/<application>/`), everything else —
  `core_ui/`, `bridge/`, `lib.rs`, `main.rs` — is the core UI. A Rust module is reached only through a path, so every
  path is resolved against the file's own module: `crate::…` (and `veyq::…`, the library as `main.rs` names it) from the
  root, `self::`/`super::…` from the file's module, a `use` group's members (`crate::{apps::depot::x, …}`) from its
  prefix, a path whose first segment is `apps` from the root. A path that lands in `apps` names that application, or —
  `crate::apps` alone, `crate::apps::{…}` — every one:
  - **(a)** no core UI file names `apps` — but the composition root, `src/applications.rs`, which calls each
    application's `register` (the web's `applications.ts`).
  - **(b)** no file of one application names another's module (nor `apps` without its own name after it), nor another
    application's part of the wire.
  - **(c)** no core UI file names an application's part of the bridge's wire (`wire_applications`): a call's, answer's or
    update's variant whose serial name's prefix is the application's (`Pot.Open` → `Call::PotOpen`, `WIRE_APPLICATIONS`),
    or a type or variant without one that starts with such a prefix (`GalleryShown`, `MemoryDates`) — read where the wire
    is named, `wire::X` (a `use` group's members too) or `Call::`/`Answer::`/`Update::`/`Watched::`/`HostRequest::`/
    `HostReply::X`. The host's bridge module (`src/bridge/`) mirrors the whole wire and is not read for it, as the Kotlin
    bridge is not. Fail closed: a dotted prefix of the wire in neither `WIRE_APPLICATIONS` nor `WIRE_PLATFORM` is red.
  - fail closed: a file under `apps/` outside an application's directory, an application without a file under
    `apps/<application>/`, and a composition root that is missing or names some application nowhere are each red.
  Windows: every `.cs` under `src/mobile/windows/Veyq/` (no `bin/`, `obj/`), read with comments and literals blanked
  (`cs_mask`; an interpolation's holes stay code); an application's UI is the folder `Apps/<Application>/` (the id
  capitalized, `Apps/Memories/`) with the namespace `Veyq.Apps.<Application>`, the core UI is `Core/` (`Veyq.Core`),
  and the root holds what the system names by class (`App`, `MainWindow`, `Program`), the words' generated half
  (`Words`) and the composition root `Applications.cs`:
  - **(a)** no file but the composition root names `Veyq.Apps` — spelled whole, or `Apps.<x>` as a namespace of
    `Veyq` reaches it from any file below `Veyq` —, and no `.xaml` does.
  - **(b)** no file of one application's UI names another's: `Veyq.Apps.<other>` or `Apps.<other>`, or `<Other>.`
    alone, which reaches the sibling namespace from inside `Veyq.Apps`; nor another application's part of the wire.
  - **(c)** no core UI file names an application's part of the wire, as Linux's (c) reads it: a variant
    (`Call.FileChooseReplace`) or — the generated `Veyq.Bridge.Wire` imported everywhere — a type bare
    (`MemoryDateShown`); a name that is a prefix alone (`File`, `Date`) is read only as a variant (.NET's `File`, a
    member's `Date`).
  - fail closed: every `namespace` a file declares (file-scoped or a block) is its folder's, so no file reaches an
    application unqualified from outside it; a `global using` naming `Veyq.Apps` anywhere, or a project's `<Using
    Include="Veyq.Apps…">`, is red (it would import an application into every file); a folder under `Apps/` that is no
    application, an application without a file there, and a composition root that is missing or does not name every
    application's namespace are each red.

**The defect that bought it.** No incident — built with cr-apps-core-ui-layer (2026-11-20) because the apps had no
direction guard but the search's (`validate_mobile_search_names_no_application.py`, which this one extends to the
rest of core): at that CR's refine the Kotlin core's `memories/GalleryModel` imported `depot.FileSave` (an application
→ application edge) and each platform was one flat directory, so nothing kept a Memories screen from drawing an
Expenses one, as the web's `validate_web_core_imports_no_applications.py` does there. Its rules over the Kotlin core moved
to `validate_mobile_module_graph.py` when the core split into modules, where the compiler refuses an import the build
graph does not carry.

**Its limit.** It reads names, not types: an application's class reached through a type alias, an `Any` handed over and
cast, or a kind spelled as a literal (`"MEMORY"`, which the mask blanks) is not seen; on Android a core application's
type reached without its name — an inferred `val` holding what a composition function returns (`ShellModel.fileModel`) —
is not either. Apple's (c) reads names, not Swift's resolution: a Kotlin nested type spelled concatenated
(`GalleryModelState` for `GalleryModel.State`) and an application's extension property (a `.member` without a call) are
not seen. The desktops' (c) reads the wire's serial names: a type the wire carries from an application's package as a
field (`PotView.SettleRow`) is owned by no prefix and is not seen, nor is a prefix-less name that starts with a platform
prefix (`PersonMemoriesShown`, Memories' section of a person's page); a shared answer is named in `WIRE_SHARED`. The
bridge module (`src/mobile/bridge`, the desktops' dispatch over every application) is not read: it is a host, a
composition root by its nature. A platform whose rules are not switched on (`PLATFORMS`) is not read at all. On Linux a `#[path]`
attribute that mounts another directory's file, an item re-exported by core under another name (`pub use`) and a
macro that expands to a path are not seen; a kind literal (`"EXPENSES_POT"`, blanked) is not either. Apple's index sees names,
not Swift's resolution: a member an application adds inside a type it declares is reached only through that type
(seen), but a protocol conformance's requirement or a name read inside a string interpolation's nested literal is not,
and an application's extension member that core also declares by name is not counted (it would be every `.body`).
On Windows a type reached by its name as a string (`Type.GetType("Veyq.Apps…")`, reflection) is not seen, nor an
application's type handed to core as `object` and cast back; `Veyq.Tests` (it compiles some application files beside
core ones, a consumer of both) is not read.
"""
from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import repo_layout  # noqa: E402
from kotlin_lexer import mask  # noqa: E402
from rust_lexer import mask as mask_rust  # noqa: E402

MOBILE = repo_layout.MOBILE
APPLICATIONS = repo_layout.MOBILE_CORE / "src/commonMain/kotlin/me/raddatz/veyq/mobile/navigation/Applications.kt"
ENTRY = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(\s*\"([a-z][a-z0-9]*)\"\s*\)\s*,", re.MULTILINE)
PACKAGE_DECL = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)

# The platforms whose UI rules (a) and (b) are on; each is switched on in its phase of cr-apps-core-ui-layer.
PLATFORMS: dict[str, bool] = {"android": True, "apple": True, "linux": True, "windows": True}

ANDROID_SRC = MOBILE / "android" / "src"
ANDROID_PACKAGE = "me.raddatz.veyq.mobile.android"
CORE_PACKAGE = "me.raddatz.veyq.mobile"
ANDROID_BASE = pathlib.Path("kotlin/me/raddatz/veyq/mobile/android")
ANDROID_ROOT = pathlib.Path("main") / ANDROID_BASE / "Applications.kt"

LINUX_SRC = repo_layout.MOBILE_LINUX / "src"
LINUX_ROOT = pathlib.Path("applications.rs")
RUST_TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|::|[{},;]|\S")
APPLE_DIR = MOBILE / "apple"
APPLE_SOURCES = ("Veyq", "VeyqShare")
APPLE_APPS = pathlib.Path("Veyq") / "Apps"
APPLE_ROOT = pathlib.Path("Veyq") / "Applications.swift"
WINDOWS = repo_layout.MOBILE_WINDOWS
WINDOWS_APP = WINDOWS / "Veyq"
WINDOWS_ROOT = "Veyq"
WINDOWS_COMPOSITION = pathlib.Path("Applications.cs")
CS_NAMESPACE = re.compile(r"\bnamespace\s+([\w.\s]+?)\s*[;{]")
CS_GLOBAL_USING = re.compile(r"\bglobal\s+using\b[^;]*;")



def applications(source: str) -> list[str]:
    """The application ids `ApplicationId`'s entries carry (each one the name of its package)."""
    m = re.search(r"enum class ApplicationId\b[^{]*\{(.*?)\n\s*;", source, re.DOTALL)
    return [] if m is None else [e.group(2) for e in ENTRY.finditer(m.group(1))]


def _dotted(name: str) -> str:
    """A dotted name as a regex that allows whitespace around the dots, as Kotlin does."""
    return r"\s*\.\s*".join(map(re.escape, name.split(".")))


def _line(text: str, at: int) -> int:
    return text.count("\n", 0, at) + 1


def _rel(p: pathlib.Path) -> str:
    return str(p.relative_to(repo_layout.ROOT)) if p.is_relative_to(repo_layout.ROOT) else str(p)


def _package_of(masked: str) -> str | None:
    m = PACKAGE_DECL.search(masked)
    return None if m is None else re.sub(r"\s+", "", m.group(1))


def android_sources(android_src: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    out: list[tuple[str, pathlib.Path]] = []
    for source_set in sorted(p for p in android_src.iterdir() if p.is_dir() and "test" not in p.name.lower()):
        base = source_set / ANDROID_BASE
        if base.is_dir():
            out += [(source_set.name, f) for f in sorted(base.rglob("*.kt"))]
    return out


def check_android(android_src: pathlib.Path, apps: list[str]) -> list[str]:
    errors: list[str] = []
    files = android_sources(android_src)
    if not files:
        return [f"{_rel(android_src)}: no Kotlin file under */{ANDROID_BASE} — the guard would check nothing"]
    apps_ref = re.compile(r"\b" + _dotted(ANDROID_PACKAGE + ".apps") + r"(?:\s*\.\s*(\w+))?")
    core_app_ref = re.compile(
        r"\b" + _dotted(CORE_PACKAGE) + r"\s*\.\s*(?:api\s*\.\s*)?(" + "|".join(map(re.escape, apps)) + r")\b"
    )
    root = android_src / ANDROID_ROOT
    populated: set[str] = set()
    for source_set, f in files:
        under = f.relative_to(android_src / source_set / ANDROID_BASE)
        expected = ".".join([ANDROID_PACKAGE, *under.parent.parts])
        masked = mask(f.read_text(encoding="utf-8"))
        declared = _package_of(masked)
        if declared != expected:
            errors.append(f"{_rel(f)}: declares package `{declared}`, its directory is `{expected}` — a package is its directory's")
            continue
        own: str | None = None
        if under.parts[0] == "apps":
            if len(under.parts) < 3 or under.parts[1] not in apps:
                errors.append(f"{_rel(f)}: under `apps/` but in no application's directory (`apps/<{'|'.join(apps)}>/`)")
                continue
            own = under.parts[1]
            populated.add(own)
        if f == root:
            continue
        for m in apps_ref.finditer(masked):
            target = m.group(1)
            if own is None:
                errors.append(
                    f"{_rel(f)}:{_line(masked, m.start())}: (a) the core UI names `{ANDROID_PACKAGE}.apps"
                    f"{'.' + target if target else ''}` — it reaches an application only through the registry "
                    f"its composition root `{ANDROID_ROOT.name}` fills",
                )
            elif target != own:
                errors.append(
                    f"{_rel(f)}:{_line(masked, m.start())}: (b) the application `{own}` names "
                    f"`{ANDROID_PACKAGE}.apps{'.' + target if target else ''}` — another application's UI; what two "
                    f"applications draw belongs to the core UI",
                )
        for m in core_app_ref.finditer(masked):
            target = m.group(1)
            if own is None:
                errors.append(
                    f"{_rel(f)}:{_line(masked, m.start())}: (c) the core UI names the core application `{target}`'s "
                    f"package (`{CORE_PACKAGE}.{target}`, `{CORE_PACKAGE}.api.{target}`) — what it holds of an application "
                    f"the application holds in the `Holding` it registers, and the core UI asks every holding",
                )
            elif target != own:
                errors.append(
                    f"{_rel(f)}:{_line(masked, m.start())}: (b) the application `{own}` names the core application "
                    f"`{target}`'s package — another application's; what two applications share belongs to the platform",
                )
    for app in apps:
        if app not in populated:
            errors.append(f"{_rel(android_src / 'main' / ANDROID_BASE / 'apps' / app)}: the application `{app}` has no UI file under apps/")
    if not root.is_file():
        errors.append(f"{_rel(root)}: the composition root is missing — each application registers there")
    else:
        masked = mask(root.read_text(encoding="utf-8"))
        for app in apps:
            if not re.search(r"\b" + _dotted(f"{ANDROID_PACKAGE}.apps.{app}") + r"\b", masked):
                errors.append(f"{_rel(root)}: the composition root names no `{ANDROID_PACKAGE}.apps.{app}` — `{app}` is not registered")
    return errors


# ------------------------------------------------------------------------------------------------ the desktops' wire, (c)

WIRE = MOBILE / "bridge/src/commonMain/kotlin/me/raddatz/veyq/mobile/bridge/Wire.kt"
# Each serial-name prefix of the bridge's wire (`@SerialName("Pot.Open")`), by the application whose model it reaches; a
# prefix the core's own models answer is the platform's. A prefix in neither is red: the table is read whole, never guessed.
WIRE_APPLICATIONS: dict[str, tuple[str, ...]] = {
    "memories": ("Gallery", "Lightbox", "Tracks", "Track", "Banner", "Music", "Buckets", "Bucket", "Participants", "Participant",
                 "Date", "Place", "CaptureTime", "Filter", "Chip", "Marks", "Mark", "Memory", "Memories", "Medium", "Grid"),
    "expenses": ("Pot", "PotSettings", "PotCreate", "Editor", "Receipt", "Entry"),
    "depot": ("File", "DropBox"),
}
WIRE_PLATFORM = (
    "AddressChange", "Audio", "Avatar", "Bookmark", "Controls", "Cover", "Erasure", "Header", "Home", "Import", "KeyRotation",
    "Kit", "Link", "Manage", "Map", "MapState", "Media", "Move", "Net", "Notice", "PasswordChange", "Pdf", "Person",
    "Picture", "Player", "Profile", "Queued", "Reader", "Received", "Report", "Row", "Save", "Scan", "Search", "Session",
    "Settings", "Share", "Shell", "SignIn", "Store", "Trash", "Trust", "Unlock", "Upload", "View", "Words",
)
# A name of the wire that starts with an application's prefix yet is shared: Depot's `File.Pages` answers with the
# receipt viewer's `ReceiptPages` (a PDF's page budget), so it is no one application's.
WIRE_SHARED = frozenset({"ReceiptPages"})
WIRE_SERIAL = re.compile(r'@SerialName\("([A-Z][A-Za-z0-9]*)(?:\.([A-Z][A-Za-z0-9]*))?"\)')


def _owner_by_prefix(name: str, apps: list[str]) -> str | None:
    """The application whose wire prefix [name] starts with at a word's boundary (`GalleryShown` → memories, not
    `Filter`'s `File`), the longest prefix deciding; None for the platform's."""
    best: tuple[int, str] | None = None
    for app in apps:
        for prefix in WIRE_APPLICATIONS.get(app, ()):
            if name.startswith(prefix) and (len(name) == len(prefix) or not name[len(prefix)].islower()):
                if best is None or len(prefix) > best[0]:
                    best = (len(prefix), app)
    for prefix in WIRE_PLATFORM:
        if name.startswith(prefix) and (len(name) == len(prefix) or not name[len(prefix)].islower()):
            if best is not None and len(prefix) >= best[0]:
                return None
    return None if best is None else best[1]


def wire_applications(apps: list[str], wire: pathlib.Path = WIRE) -> tuple[dict[str, str], list[str]]:
    """The host names the desktops' generated wire gives each application's part, by name → application: a call's,
    answer's or update's variant (`Pot.Open` → `PotOpen`) by its prefix, and a type or variant without one
    (`GalleryShown`, `MemoryDates`) by the prefix it starts with. With the errors: a dotted prefix the table does not
    classify, or the wire not read."""
    if not wire.is_file():
        return {}, [f"{_rel(wire)}: not found — rule (c) of the desktops reads the wire's serial names"]
    owned: dict[str, str] = {}
    errors: list[str] = []
    classified = set(WIRE_PLATFORM) | {p for app in apps for p in WIRE_APPLICATIONS.get(app, ())}
    masked = mask(wire.read_text(encoding="utf-8"))
    raw = wire.read_text(encoding="utf-8")
    for m in WIRE_SERIAL.finditer(raw):
        if masked[m.start()] != "@":  # in a comment
            continue
        prefix, verb = m.group(1), m.group(2)
        if verb is not None and prefix not in classified:
            errors.append(f"{_rel(wire)}:{_line(raw, m.start())}: the wire prefix `{prefix}.` is in no application's and not the platform's (`WIRE_APPLICATIONS`/`WIRE_PLATFORM`)")
            continue
        name = prefix + (verb or "")
        owner = next((a for a in apps if prefix in WIRE_APPLICATIONS.get(a, ())), None) if verb else _owner_by_prefix(name, apps)
        if owner is not None and name not in WIRE_SHARED:
            owned[name] = owner
    for app in apps:
        if app not in WIRE_APPLICATIONS:
            errors.append(f"{_rel(wire)}: the application `{app}` has no wire prefixes in `WIRE_APPLICATIONS` — rule (c) would miss it")
    return owned, errors


RUST_WIRE_REF = re.compile(
    r"\b(?:wire\s*::\s*|(?:Call|Answer|Update|Watched|HostRequest|HostReply)\s*::\s*)([A-Z]\w*)|\bwire\s*::\s*\{([^}]*)\}"
)


def rust_wire_names(masked: str) -> list[tuple[str, int]]:
    """The wire's host names a Rust file names: through `wire::` (a `use` group's members too) or a call's, answer's or
    update's variant (`Call::PotOpen`)."""
    out: list[tuple[str, int]] = []
    for m in RUST_WIRE_REF.finditer(masked):
        if m.group(1):
            out.append((m.group(1), m.start(1)))
        else:
            for g in re.finditer(r"\b([A-Z]\w*)", m.group(2)):
                out.append((g.group(1), m.start(2) + g.start(1)))
    return out


def rust_module(rel: pathlib.Path) -> list[str]:
    """The module path of the crate's file at [rel] (under `src/`): `lib.rs`/`main.rs` the root, `x/mod.rs` the module x."""
    parts = list(rel.with_suffix("").parts)
    if parts in (["lib"], ["main"]):
        return []
    return parts[:-1] if parts[-1] == "mod" else parts


def rust_paths(masked: str, module: list[str]) -> list[tuple[list[str], int]]:
    """Every path of [masked] resolved to the crate's root, with its offset: `crate`/`veyq` from the root, `self`/`super`
    from [module], a `use` group's members from its prefix, a path starting with `apps` from the root; a path through
    `::` only (a bare name is no path), so a local named `apps` is none."""
    tokens = [(m.group(0), m.start()) for m in RUST_TOKEN.finditer(masked)]
    out: list[tuple[list[str], int]] = []
    groups: list[list[str] | None] = []  # per open brace: the `use` group's resolved prefix, or None for a block
    i, n = 0, len(tokens)
    while i < n:
        tok, at = tokens[i]
        if tok == "{":
            groups.append(None)
            i += 1
            continue
        if tok == "}":
            if groups:
                groups.pop()
            i += 1
            continue
        if not (tok[0].isalpha() or tok[0] == "_") or (i > 0 and tokens[i - 1][0] == "::"):
            i += 1
            continue
        segs = [tok]
        j = i + 1
        while j + 1 < n and tokens[j][0] == "::" and (tokens[j + 1][0][0].isalpha() or tokens[j + 1][0][0] == "_"):
            segs.append(tokens[j + 1][0])
            j += 2
        opens_group = j + 1 < n and tokens[j][0] == "::" and tokens[j + 1][0] == "{"
        prefix = groups[-1] if groups and i > 0 and tokens[i - 1][0] in ("{", ",") else None
        if prefix is not None:
            resolved: list[str] | None = prefix + ([] if segs == ["self"] else segs)
        elif len(segs) == 1 and not opens_group:
            resolved = None  # a bare name: no path
        elif segs[0] in ("crate", "veyq"):
            resolved = segs[1:]
        elif segs[0] in ("self", "super"):
            base = list(module)
            k = 0
            if segs[0] == "self":
                k = 1
            while k < len(segs) and segs[k] == "super":
                base = base[:-1]
                k += 1
            resolved = base + segs[k:]
        elif segs[0] == "apps":
            resolved = segs
        else:
            resolved = None  # an outside crate's path, or a name a `use` brought in (the `use` is read itself)
        if resolved is not None:
            out.append((resolved, at))
        if opens_group:
            groups.append(resolved if resolved is not None else [])
            i = j + 2
            continue
        i = j
    return out


def linux_sources(linux_src: pathlib.Path) -> list[pathlib.Path]:
    return sorted(linux_src.rglob("*.rs")) if linux_src.is_dir() else []


def check_linux(linux_src: pathlib.Path, apps: list[str], wire: pathlib.Path = WIRE) -> list[str]:
    errors: list[str] = []
    files = linux_sources(linux_src)
    if not files:
        return [f"{_rel(linux_src)}: no Rust file — the guard would check nothing"]
    owned, wire_errors = wire_applications(apps, wire)
    errors += wire_errors
    root = linux_src / LINUX_ROOT
    populated: set[str] = set()
    for f in files:
        rel = f.relative_to(linux_src)
        own: str | None = None
        if rel.parts[0] == "apps":
            if len(rel.parts) < 3 or rel.parts[1] not in apps:
                errors.append(f"{_rel(f)}: under `apps/` but in no application's directory (`apps/<{'|'.join(apps)}>/`)")
                continue
            own = rel.parts[1]
            populated.add(own)
        if f == root:
            continue
        masked = mask_rust(f.read_text(encoding="utf-8"))
        # (c): the host's bridge module (`src/bridge/`) mirrors the whole wire, a host of every application as the Kotlin
        # bridge is; every other file names an application's part of the wire only inside that application
        if rel.parts[0] != "bridge":
            for name, at in rust_wire_names(masked):
                target = owned.get(name)
                if target is None or target == own:
                    continue
                if own is None:
                    errors.append(
                        f"{_rel(f)}:{_line(masked, at)}: (c) the core UI names `{name}`, the core application {target}'s part of "
                        f"the wire — it reaches an application only through the registry its composition root `{LINUX_ROOT}` fills",
                    )
                else:
                    errors.append(
                        f"{_rel(f)}:{_line(masked, at)}: (b) the application `{own}` names `{name}`, {target}'s part of the wire",
                    )
        for path, at in rust_paths(masked, rust_module(rel)):
            if not path or path[0] != "apps":
                continue
            target = path[1] if len(path) > 1 else None
            named = "apps" + (f"::{target}" if target else "")
            if own is None:
                errors.append(
                    f"{_rel(f)}:{_line(masked, at)}: (a) the core UI names `{named}` — it reaches an application only "
                    f"through the registry its composition root `{LINUX_ROOT}` fills",
                )
            elif target != own:
                errors.append(
                    f"{_rel(f)}:{_line(masked, at)}: (b) the application `{own}` names `{named}` — another "
                    f"application's UI; what two applications draw belongs to the core UI",
                )
    for app in apps:
        if app not in populated:
            errors.append(f"{_rel(linux_src / 'apps' / app)}: the application `{app}` has no UI file under apps/")
    if not root.is_file():
        errors.append(f"{_rel(root)}: the composition root is missing — each application registers there")
    else:
        named = {p[1] for p, _ in rust_paths(mask_rust(root.read_text(encoding="utf-8")), rust_module(LINUX_ROOT))
                 if len(p) > 1 and p[0] == "apps"}
        for app in apps:
            if app not in named:
                errors.append(f"{_rel(root)}: the composition root names no `crate::apps::{app}` — `{app}` is not registered")
    return errors


# ---------------------------------------------------------------------------------------------------------------- Apple

KOTLIN_MODIFIERS = (
    r"(?:(?:public|protected|abstract|open|sealed|data|enum|annotation|inline|value|const|expect|actual|external|suspend"
    r"|operator|infix|tailrec|fun(?=\s+interface))\s+)*"
)
KOTLIN_TOP = re.compile(
    r"^((?:@[\w.]+(?:\([^)\n]*\))?\s*\n?)*)(?:(private|internal)\s+)?" + KOTLIN_MODIFIERS
    + r"(class|interface|object|typealias|fun|val|var)\s+(?:<[^>\n]*>\s*)?(?:([\w.<>?, ]+?)\.)?([A-Za-z_]\w*)",
    re.MULTILINE,
)
KOTLIN_HIDDEN = re.compile(r"@(?:HiddenFromObjC|PlatformApi)\b")


def kotlin_exports(module: pathlib.Path) -> tuple[set[str], set[str]]:
    """The names a Kotlin module of the core hands Swift through the framework: its non-private, non-internal top-level
    classes, interfaces, objects and type aliases and each file's facade (`GalleryModelKt`, which holds its top-level
    functions), as `top`; the names of its top-level extension functions and properties (`AppleSessions.openReceipt`
    reached as `.openReceipt`), as `members`. A declaration marked `@HiddenFromObjC` or `@PlatformApi` (hidden from
    Objective-C, ADR 0156 decision 4) is no export. Test source sets are not read."""
    top: set[str] = set()
    members: set[str] = set()
    src = module / "src"
    if not src.is_dir():
        return top, members
    for source_set in sorted(p for p in src.iterdir() if p.is_dir() and "test" not in p.name.lower()):
        for f in sorted(source_set.rglob("*.kt")):
            masked = mask(f.read_text(encoding="utf-8"))
            facade = False
            for m in KOTLIN_TOP.finditer(masked):
                annotations, visibility, kind, receiver, name = m.groups()
                if visibility or KOTLIN_HIDDEN.search(annotations or ""):
                    continue
                if kind in ("fun", "val", "var"):
                    if receiver:
                        members.add(name)
                    else:
                        facade = True
                else:
                    top.add(name)
            if facade:
                top.add(f.stem[:1].upper() + f.stem[1:] + "Kt")
    return top, members


def core_application_exports(apps: list[str], mobile: pathlib.Path = MOBILE) -> dict[str, tuple[set[str], set[str]]]:
    """Per application, what its core module (`src/mobile/apps/<application>`) hands Swift (`kotlin_exports`), less what
    the platform or the composition hands under the same name (Kotlin/Native renames one of a clashing pair, and a member
    name the platform also declares is no application's mark)."""
    platform_top: set[str] = set()
    platform_members: set[str] = set()
    for module in (mobile / "core", mobile / "composition"):
        t, ms = kotlin_exports(module)
        platform_top |= t
        platform_members |= ms
        src = module / "src"
        if src.is_dir():  # any function or property the platform names: a member call of that name is no application's
            for f in src.rglob("*.kt"):
                platform_members |= set(re.findall(r"\b(?:fun|val|var)\s+(?:<[^>\n]*>\s*)?(?:[\w.<>?, ]+?\.)?([a-z_]\w*)", mask(f.read_text(encoding="utf-8"))))
    out: dict[str, tuple[set[str], set[str]]] = {}
    for app in apps:
        t, ms = kotlin_exports(mobile / "apps" / app)
        out[app] = (t - platform_top, ms - platform_members)
    return out


_SWIFT_CODE = re.compile(r'//|/\*|#+"|"""|["`{}()]')
_SWIFT_BLOCK = re.compile(r"/\*|\*/")


def swift_mask(src: str) -> str:
    """`src` with comments and string contents blanked at unchanged length — but a string's `\\(…)` interpolation, which
    is code, kept (nested literals in it blanked in turn). Knows `//`, nested `/* */`, `"…"`, `\"\"\"…\"\"\"`, raw
    `#"…"#` (no interpolation read) and backticked names; Swift has no character literal."""
    out = list(src)
    n = len(src)

    def blank(a: int, b: int) -> None:
        for k in range(a, b):
            if out[k] != "\n":
                out[k] = " "

    def string(i: int, close: str, raw: bool) -> int:
        """From just inside the opening quote: blanks the contents, returns the index past the closing delimiter."""
        start = i
        while i < n:
            if src.startswith(close, i):
                blank(start, i)
                return i + len(close)
            c = src[i]
            if c == "\n" and close == '"':  # a one-line string never spans a line: malformed, stop here
                blank(start, i)
                return i
            if c == "\\" and not raw:
                if src.startswith("(", i + 1):
                    blank(start, i + 2)
                    i = code(i + 2, until_paren=True)  # at the `)` closing the interpolation
                    start = i + 1
                    i += 1
                    continue
                i += 2
                continue
            i += 1
        blank(start, n)
        return n

    def code(i: int, until_paren: bool) -> int:
        depth = 0
        while True:
            m = _SWIFT_CODE.search(src, i)
            if not m:
                return n
            t, s = m.group(), m.start()
            if t == "//":
                e = src.find("\n", s)
                i = n if e == -1 else e
                blank(s, i)
            elif t == "/*":
                d, i = 0, n
                for b in _SWIFT_BLOCK.finditer(src, s):
                    d += 1 if b.group() == "/*" else -1
                    if d == 0:
                        i = b.end()
                        break
                blank(s, i)
            elif t.startswith("#"):
                hashes = t[:-1]
                multi = src.startswith('""', m.end())
                close = ('"""' if multi else '"') + hashes
                i = string(m.end() + (2 if multi else 0), close, raw=True)
            elif t == '"""':
                i = string(m.end(), '"""', raw=False)
            elif t == '"':
                i = string(m.end(), '"', raw=False)
            elif t == "`":
                e = src.find("`", s + 1)
                i = n if e == -1 else e + 1
            elif t == "(":
                depth += 1
                i = m.end()
            elif t == ")":
                if until_paren and depth == 0:
                    return s
                depth -= 1
                i = m.end()
            else:
                i = m.end()

    code(0, until_paren=False)
    return "".join(out)


_SWIFT_MODS = r"(?:@\w+(?:\([^)\n]*\))?\s+|(?:public|internal|private|fileprivate|final|nonisolated|static|indirect|open)\s+)*"
_SWIFT_DECL = re.compile(
    r"^[ \t]*" + _SWIFT_MODS + r"(struct|class|enum|protocol|actor|typealias|func|let|var|extension)\s+([A-Za-z_]\w*)",
    re.MULTILINE,
)
_SWIFT_PRIVATE = re.compile(r"\b(?:private|fileprivate)\b")


def _depths(masked: str) -> list[int]:
    """The brace depth at the start of each line (index 0 = line 1)."""
    out, depth = [], 0
    for line in masked.split("\n"):
        out.append(depth)
        depth += line.count("{") - line.count("}")
    return out


class SwiftFile:
    def __init__(self, path: pathlib.Path):
        self.path = path
        self.masked = swift_mask(path.read_text(encoding="utf-8"))
        depths = _depths(self.masked)
        self.top: set[str] = set()       # non-private top-level names: types, functions, globals
        self.members: set[str] = set()   # non-private members a top-level extension adds
        self.declared: set[str] = set()  # every name declared anywhere in the file, any depth or visibility
        in_extension: int | None = None  # the line where the open top-level extension began
        for m in _SWIFT_DECL.finditer(self.masked):
            kind, name = m.group(1), m.group(2)
            line = _line(self.masked, m.start())
            depth = depths[line - 1]
            private = _SWIFT_PRIVATE.search(m.group(0)) is not None
            if kind != "extension":
                self.declared.add(name)
            if depth == 0:
                in_extension = line if kind == "extension" else None
                if kind != "extension" and not private:
                    self.top.add(name)
            elif depth == 1 and in_extension is not None and kind in ("func", "var", "let") and not private:
                self.members.add(name)


def apple_sources(apple_dir: pathlib.Path) -> list[pathlib.Path]:
    out: list[pathlib.Path] = []
    for d in APPLE_SOURCES:
        if (apple_dir / d).is_dir():
            out += sorted((apple_dir / d).rglob("*.swift"))
    return out


def check_apple(apple_dir: pathlib.Path, apps: list[str], mobile: pathlib.Path = MOBILE) -> list[str]:
    errors: list[str] = []
    paths = apple_sources(apple_dir)
    if not paths:
        return [f"{_rel(apple_dir)}: no Swift file under {'/ '.join(APPLE_SOURCES)} — the guard would check nothing"]
    groups = {app[:1].upper() + app[1:]: app for app in apps}
    files: list[tuple[str | None, SwiftFile]] = []
    for f in paths:
        under = f.relative_to(apple_dir)
        own: str | None = None
        if under.parts[: len(APPLE_APPS.parts)] == APPLE_APPS.parts:
            rest = under.parts[len(APPLE_APPS.parts):]
            if len(rest) < 2 or rest[0] not in groups:
                errors.append(f"{_rel(f)}: under `{APPLE_APPS}/` but in no application's group (`{APPLE_APPS}/<{'|'.join(groups)}>/`)")
                continue
            own = groups[rest[0]]
        files.append((own, SwiftFile(f)))
    root = apple_dir / APPLE_ROOT
    owners: dict[str, list[str]] = {}
    for own, sf in files:
        for name in sf.top:
            owners.setdefault(name, []).append(own or "core")
    index: dict[str, dict[str, set[str]]] = {app: {"top": set(), "members": set()} for app in apps}
    core_declared: set[str] = set()
    for own, sf in files:
        if own is None:
            core_declared |= sf.declared
        else:
            index[own]["top"] |= sf.top
            index[own]["members"] |= sf.members
    for name, sides in sorted(owners.items()):
        if len(set(sides)) > 1 and any(s != "core" for s in sides):
            errors.append(
                f"{_rel(apple_dir)}: `{name}` is declared at the top level by {', '.join(sorted(set(sides)))} — the index "
                f"cannot tell them apart; what two sides use belongs to the core UI, an application's own takes its own name",
            )
    for app in apps:
        # an application's `AppModel.<id>` (its holding) shares its name with `ApplicationId.<id>`, which core names freely
        index[app]["members"] -= core_declared | set(apps)
    for app in apps:
        if not any(own == app for own, _ in files):
            errors.append(f"{_rel(apple_dir / APPLE_APPS / app.capitalize())}: the application `{app}` has no UI file under {APPLE_APPS}/")
    patterns = {
        app: (
            re.compile(r"(?<![\w.])(" + "|".join(sorted(map(re.escape, index[app]["top"]))) + r")\b") if index[app]["top"] else None,
            re.compile(r"\.(" + "|".join(sorted(map(re.escape, index[app]["members"]))) + r")\b") if index[app]["members"] else None,
        )
        for app in apps
    }
    for own, sf in files:
        if sf.path == root:
            continue
        for app in apps:
            if app == own:
                continue
            for pattern in patterns[app]:
                if pattern is None:
                    continue
                for m in pattern.finditer(sf.masked):
                    if m.group(1) in sf.declared:
                        continue
                    where = f"{_rel(sf.path)}:{_line(sf.masked, m.start())}"
                    if own is None:
                        errors.append(
                            f"{where}: (a) the core UI names `{m.group(1)}`, {app}'s — it reaches an application only "
                            f"through the registry its composition root `{APPLE_ROOT.name}` fills",
                        )
                    else:
                        errors.append(
                            f"{where}: (b) the application `{own}` names `{m.group(1)}`, {app}'s — what two applications "
                            f"draw belongs to the core UI",
                        )
    # (c): the core applications' Kotlin types as the framework hands them to Swift (one flat module, no imports)
    exports = core_application_exports(apps, mobile)
    for app in apps:
        if not exports[app][0]:
            errors.append(f"{_rel(mobile / 'apps' / app)}: no Kotlin export of `{app}` read — rule (c) would check nothing")
    kotlin_patterns = {
        app: (
            re.compile(r"(?<![\w.])(" + "|".join(sorted(map(re.escape, exports[app][0]))) + r")\b") if exports[app][0] else None,
            re.compile(r"\.(" + "|".join(sorted(map(re.escape, exports[app][1]))) + r")\s*\(") if exports[app][1] else None,
        )
        for app in apps
    }
    for own, sf in files:
        if sf.path == root:
            continue
        for app in apps:
            if app == own:
                continue
            for pattern in kotlin_patterns[app]:
                if pattern is None:
                    continue
                for m in pattern.finditer(sf.masked):
                    if m.group(1) in sf.declared:
                        continue
                    where = f"{_rel(sf.path)}:{_line(sf.masked, m.start())}"
                    if own is None:
                        errors.append(
                            f"{where}: (c) the core UI names `{m.group(1)}`, the core application {app}'s (Kotlin, through the "
                            f"framework) — what it holds of an application the application holds in the `Holding` it registers",
                        )
                    else:
                        errors.append(
                            f"{where}: (b) the application `{own}` names `{m.group(1)}`, the core application {app}'s — what "
                            f"two applications share belongs to the platform",
                        )
    if not root.is_file():
        errors.append(f"{_rel(root)}: the composition root is missing — each application registers there")
    else:
        masked = swift_mask(root.read_text(encoding="utf-8"))
        for app in apps:
            top = patterns[app][0]
            if top is None or not top.search(masked):
                errors.append(f"{_rel(root)}: the composition root names nothing of `{app}` — it is not registered")
    return errors


def cs_mask(src: str) -> str:
    """`src` with every comment and every string/char literal's text blanked, at unchanged length; an interpolated
    string's holes (`$"…{expr}…"`) stay code, read as any other, so a name spelled in a hole is still seen. It knows
    `//` and `/* */` comments, `'…'`, `"…"` with escapes, verbatim `@"…"` (`""` an escaped quote), `$`/`$@`/`@$`
    interpolation (`{{` a literal brace) and raw strings, three quotes or more (their holes blanked too) —
    nothing else."""
    out = list(src)
    n = len(src)

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    def code(i: int, close: str | None) -> int:
        """Reads code from `i` to the brace that closes a hole (`close`), or to the end; returns the index past it."""
        depth = 0
        while i < n:
            c = src[i]
            if src.startswith("//", i):
                e = src.find("\n", i)
                e = n if e < 0 else e
                blank(i, e)
                i = e
            elif src.startswith("/*", i):
                e = src.find("*/", i + 2)
                e = n if e < 0 else e + 2
                blank(i, e)
                i = e
            elif c == "'":
                j = i + 1
                while j < n and src[j] != "'":
                    j += 2 if src[j] == "\\" else 1
                blank(i + 1, j)
                i = j + 1
            elif c in "$@\"":
                j = i
                while j < n and src[j] in "$@":
                    j += 1
                if j < n and src[j] == '"' and (j == i or not (src[i - 1].isalnum() or src[i - 1] == "_") if i > 0 else True):
                    i = string(i, j)
                else:
                    i += 1
            elif close is not None and c == "{":
                depth += 1
                i += 1
            elif close is not None and c == "}":
                if depth == 0:
                    return i + 1
                depth -= 1
                i += 1
            else:
                i += 1
        return i

    def string(start: int, quote: int) -> int:
        prefix = src[start:quote]
        dollars = prefix.count("$")
        verbatim = "@" in prefix
        q = quote
        while q < n and src[q] == '"':
            q += 1
        quotes = q - quote
        if quotes >= 3:  # raw: closed by as many quotes; its holes blanked with it
            e = src.find('"' * quotes, q)
            e = n if e < 0 else e
            blank(q, e)
            return e + quotes
        if quotes == 2 and not verbatim:  # ""
            return q
        i = quote + 1
        while i < n:
            c = src[i]
            if c == '"':
                if verbatim and i + 1 < n and src[i + 1] == '"':
                    blank(i, i + 2)
                    i += 2
                    continue
                return i + 1
            if c == "\\" and not verbatim:
                blank(i, i + 2)
                i += 2
            elif dollars and c == "{":
                if i + 1 < n and src[i + 1] == "{":
                    blank(i, i + 2)
                    i += 2
                else:
                    i = code(i + 1, "}")
            elif dollars and c == "}" and i + 1 < n and src[i + 1] == "}":
                blank(i, i + 2)
                i += 2
            elif c == "\n" and not verbatim:
                return i
            else:
                blank(i, i + 1)
                i += 1
        return i

    code(0, None)
    return "".join(out)


def _pascal(app: str) -> str:
    return app[:1].upper() + app[1:]


def windows_sources(app_dir: pathlib.Path, suffix: str = ".cs") -> list[pathlib.Path]:
    if not app_dir.is_dir():
        return []
    return sorted(p for p in app_dir.rglob(f"*{suffix}") if not {"bin", "obj"} & set(p.relative_to(app_dir).parts))


CS_WIRE_VARIANT = re.compile(r"(?<![\w.])(?:Call|Answer|Update|Watched|HostRequest|HostReply)\s*\.\s*([A-Z]\w*)")
CS_DECLARED = re.compile(r"\b(?:class|struct|record|enum|interface|delegate\s+\w+)\s+([A-Z]\w*)")


def check_windows(app_dir: pathlib.Path, apps: list[str], wire: pathlib.Path = WIRE) -> list[str]:
    errors: list[str] = []
    files = windows_sources(app_dir)
    if not files:
        return [f"{_rel(app_dir)}: no C# file — the guard would check nothing"]
    owned, wire_errors = wire_applications(apps, wire)
    errors += wire_errors
    # a type is named bare, but a name that is a prefix alone (`File`, `Date`, `Music`: an update's variant) is also .NET's
    # or a member's: those are read only as a variant (`Update.File`)
    prefixes = {p for ps in WIRE_APPLICATIONS.values() for p in ps}
    bare = sorted(n for n in owned if n not in prefixes)
    owned_bare = re.compile(r"(?<![\w.])(" + "|".join(map(re.escape, bare)) + r")\b") if bare else None
    folders = {_pascal(a): a for a in apps}
    apps_ref = re.compile(
        r"(?<![\w.])(?:global\s*::\s*)?(?:" + _dotted(WINDOWS_ROOT + ".Apps") + r"|Apps(?=\s*\.))(?:\s*\.\s*(\w+))?",
    )
    root = app_dir / WINDOWS_COMPOSITION
    populated: set[str] = set()
    for f in files:
        under = f.relative_to(app_dir)
        expected = ".".join([WINDOWS_ROOT, *under.parent.parts])
        masked = cs_mask(f.read_text(encoding="utf-8-sig"))
        declared = [re.sub(r"\s+", "", m.group(1)) for m in CS_NAMESPACE.finditer(masked)]
        if not declared:
            errors.append(f"{_rel(f)}: declares no namespace — a file's namespace is its folder's (`{expected}`)")
            continue
        wrong = [d for d in declared if d != expected]
        if wrong:
            errors.append(f"{_rel(f)}: declares namespace `{wrong[0]}`, its folder is `{expected}` — a namespace is its folder's")
            continue
        for m in CS_GLOBAL_USING.finditer(masked):
            if apps_ref.search(m.group(0)):
                errors.append(f"{_rel(f)}:{_line(masked, m.start())}: a `global using` of `{WINDOWS_ROOT}.Apps` imports an application into every file")
        own: str | None = None
        if under.parts[0] == "Apps":
            if len(under.parts) < 3 or under.parts[1] not in folders:
                errors.append(f"{_rel(f)}: under `Apps/` but in no application's folder (`Apps/<{'|'.join(folders)}>/`)")
                continue
            own = under.parts[1]
            populated.add(own)
        if f == root:
            continue
        # (c): an application's part of the wire — a variant (`Call.PotOpen`) or a type (`MemoryDateShown`) — named only
        # inside that application (the generated `Veyq.Bridge.Wire` is imported everywhere, so a type is named bare)
        own_app = folders.get(own) if own else None
        declared_here = set(CS_DECLARED.findall(masked))
        seen_at: set[int] = set()
        hits = [(m.group(1), m.start(1)) for m in CS_WIRE_VARIANT.finditer(masked)]
        if owned_bare is not None:
            hits += [(m.group(1), m.start(1)) for m in owned_bare.finditer(masked)]
        for name, at in sorted(hits, key=lambda h: h[1]):
            target = owned.get(name)
            if target is None or target == own_app or at in seen_at or name in declared_here:
                continue
            seen_at.add(at)
            if own is None:
                errors.append(
                    f"{_rel(f)}:{_line(masked, at)}: (c) the core UI names `{name}`, the core application {target}'s part of the "
                    f"wire — it reaches an application only through the registry its composition root `{WINDOWS_COMPOSITION}` fills",
                )
            else:
                errors.append(f"{_rel(f)}:{_line(masked, at)}: (b) the application `{own}` names `{name}`, {target}'s part of the wire")
        for m in apps_ref.finditer(masked):
            target = m.group(1)
            spelled = f"{WINDOWS_ROOT}.Apps{'.' + target if target else ''}"
            if own is None:
                errors.append(
                    f"{_rel(f)}:{_line(masked, m.start())}: (a) the core UI names `{spelled}` — it reaches an application "
                    f"only through the registry its composition root `{WINDOWS_COMPOSITION}` fills",
                )
            elif target != own:
                errors.append(
                    f"{_rel(f)}:{_line(masked, m.start())}: (b) the application `{own}` names `{spelled}` — another "
                    f"application's UI; what two applications draw belongs to the core UI",
                )
        if own is not None:
            others = [p for p in folders if p != own]
            if others:
                for m in re.finditer(r"(?<![\w.])(" + "|".join(others) + r")\s*\.", masked):
                    errors.append(
                        f"{_rel(f)}:{_line(masked, m.start())}: (b) the application `{own}` names `{m.group(1)}.` — the "
                        f"sibling namespace `{WINDOWS_ROOT}.Apps.{m.group(1)}`, another application's UI",
                    )
    for x in windows_sources(app_dir, ".xaml"):
        text = re.sub(r"<!--.*?-->", lambda m: re.sub(r"[^\n]", " ", m.group(0)), x.read_text(encoding="utf-8-sig"), flags=re.S)
        for m in re.finditer(_dotted(WINDOWS_ROOT + ".Apps"), text):
            errors.append(f"{_rel(x)}:{_line(text, m.start())}: (a) a XAML file names `{WINDOWS_ROOT}.Apps` — only the composition root does")
    for project in sorted(app_dir.parent.glob("*.props")) + sorted(app_dir.glob("*.csproj")):
        text = project.read_text(encoding="utf-8-sig")
        for m in re.finditer(r"<Using\b[^>]*\b" + _dotted(WINDOWS_ROOT + ".Apps"), text):
            errors.append(f"{_rel(project)}:{_line(text, m.start())}: a project-wide `<Using>` of `{WINDOWS_ROOT}.Apps` imports an application into every file")
    for folder in folders:
        if folder not in populated:
            errors.append(f"{_rel(app_dir / 'Apps' / folder)}: the application `{folders[folder]}` has no UI file under Apps/")
    if not root.is_file():
        errors.append(f"{_rel(root)}: the composition root is missing — each application registers there")
    else:
        masked = cs_mask(root.read_text(encoding="utf-8-sig"))
        for folder in folders:
            if not re.search(r"\b" + _dotted(f"{WINDOWS_ROOT}.Apps.{folder}") + r"\b", masked):
                errors.append(f"{_rel(root)}: the composition root names no `{WINDOWS_ROOT}.Apps.{folder}` — `{folders[folder]}` is not registered")
    return errors


def check(
    applications_file: pathlib.Path = APPLICATIONS,
    platforms: dict[str, bool] | None = None,
    android_src: pathlib.Path = ANDROID_SRC,
    linux_src: pathlib.Path = LINUX_SRC,
    apple_dir: pathlib.Path = APPLE_DIR,
    windows_app: pathlib.Path = WINDOWS_APP,
) -> list[str]:
    if not applications_file.is_file():
        return [f"{_rel(applications_file)}: not found — the applications are derived from ApplicationId's entries"]
    apps = applications(applications_file.read_text(encoding="utf-8"))
    if not apps:
        return [f"{_rel(applications_file)}: no ApplicationId entry read — the guard would check nothing"]
    errors: list[str] = []
    on = PLATFORMS if platforms is None else platforms
    if on.get("android"):
        errors += check_android(android_src, apps)
    if on.get("linux"):
        errors += check_linux(linux_src, apps)
    if on.get("apple"):
        errors += check_apple(apple_dir, apps)
    if on.get("windows"):
        errors += check_windows(windows_app, apps)
    return errors


def main() -> int:
    errors = check()
    for e in errors:
        print(e)
    if errors:
        print(f"validate_mobile_ui_direction: {len(errors)} violation(s)")
        return 1
    read = (len(android_sources(ANDROID_SRC)) if PLATFORMS.get("android") else 0) \
        + (len(linux_sources(LINUX_SRC)) if PLATFORMS.get("linux") else 0)
    read += len(apple_sources(APPLE_DIR)) if PLATFORMS.get("apple") else 0
    read += len(windows_sources(WINDOWS_APP)) if PLATFORMS.get("windows") else 0
    on = ", ".join(p for p, v in PLATFORMS.items() if v) or "none"
    print(f"validate_mobile_ui_direction: OK ({read} files; platforms on: {on})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
