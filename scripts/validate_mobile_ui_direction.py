#!/usr/bin/env python3
"""Guard that the apps' dependencies run application → core, never back and never application → application
(ADR 0154 decision 3, the apps' half of ADR 0079; cr-apps-core-ui-layer L4 and L5).

**What it asserts.** The applications are not listed here: they are derived from the entries of the Kotlin core's
`ApplicationId` (`core/…/navigation/Applications.kt`, `MEMORIES("memories")` names the package `…mobile.memories`), so
an application added there is guarded the day it lands. Every source is read with comments and literals blanked
(`kotlin_lexer.mask`), so a name in a KDoc is no reference. A reference is a name spelled with its package — an import
or a qualified name —, which in Kotlin is the only way to reach another package. Rules:

- **(c) the Kotlin core** (`src/mobile/core/src/<set>Main/kotlin/me/raddatz/veyq/mobile/`, every main source set;
  a test set is a consumer of both sides and is not read). An application's package is `…mobile.<application>` (and
  its generated client `…mobile.api.<application>`, `core/build.gradle.kts`' `api.<application>`):
  - **(c1)** no file of one application's package names another application's package or client — no exception;
    what two applications share belongs to core (`blob/FileSave` moved there for this rule).
  - **(c2)** no file outside the application packages names one, except the files of `CORE_ROOTS`, each with the
    reason it still does: `AppKinds.kt`, the composition root, and the files whose hook behind `AppKinds` needs a
    design of its own (cr-apps-core-ui-layer L4: handed back, not built in a move). A listed file that is missing
    or names no application any more is red too, so the list only shrinks.
  - every file's `package` is its directory's: a file that declared an application's package outside its
    directory (or core's inside one) would reach it unqualified, past (c1) and (c2).
- **(a) and (b) a platform's UI**, switched on per platform in its phase of cr-apps-core-ui-layer (`PLATFORMS`), so
  the guard is never born red. Android: the sources of every source set but a test one, under
  `src/mobile/android/src/<set>/kotlin/me/raddatz/veyq/mobile/android/`; an application's UI is the package
  `…android.apps.<application>` (directory `apps/<application>/`), everything else is the core UI:
  - **(a)** no core UI file names `…android.apps` — but the one composition root, `main/…/android/Applications.kt`,
    which registers each application into the core UI's registry (the web's `src/web/src/applications.ts`).
  - **(b)** no file of one application's UI names another application's UI (`…android.apps.<other>`).
  - fail closed: a directory under `apps/` that is no application, an application without a file under `apps/`,
    a composition root that is missing or does not name every application's package, and a `package` that is not
    its directory's are each red. Kotlin's packages make the import the whole edge: a symbol of another package is
    unreachable without its name spelled.

  Linux: the crate's sources under `src/mobile/linux/src/` (read with comments and literals blanked, `rust_lexer.mask`);
  an application's UI is the module `apps::<application>` (directory `src/apps/<application>/`), everything else —
  `core_ui/`, `bridge/`, `lib.rs`, `main.rs` — is the core UI. A Rust module is reached only through a path, so every
  path is resolved against the file's own module: `crate::…` (and `veyq::…`, the library as `main.rs` names it) from the
  root, `self::`/`super::…` from the file's module, a `use` group's members (`crate::{apps::depot::x, …}`) from its
  prefix, a path whose first segment is `apps` from the root. A path that lands in `apps` names that application, or —
  `crate::apps` alone, `crate::apps::{…}` — every one:
  - **(a)** no core UI file names `apps` — but the composition root, `src/applications.rs`, which calls each
    application's `register` (the web's `applications.ts`).
  - **(b)** no file of one application names another's module (nor `apps` without its own name after it).
  - fail closed: a file under `apps/` outside an application's directory, an application without a file under
    `apps/<application>/`, and a composition root that is missing or names some application nowhere are each red.

**The defect that bought it.** No incident — built with cr-apps-core-ui-layer (2026-11-20) because the apps had no
direction guard but the search's (`validate_mobile_search_names_no_application.py`, which this one extends to the
rest of core): at that CR's refine the Kotlin core's `memories/GalleryModel` imported `depot.FileSave` (an application
→ application edge) and each platform was one flat directory, so nothing kept a Memories screen from drawing an
Expenses one, as the web's `validate_web_core_imports_no_applications.py` does there.

**Its limit.** It reads names, not types: an application's class reached through a type alias declared in core, an
`Any` handed over and cast, or a kind spelled as a literal (`"MEMORY"`, which the mask blanks) is not seen — core's
kind literals are a review's catch (the web's guard has a kind rule; the apps have none yet). A star import of a parent
package (`me.raddatz.veyq.mobile.*`, then `memories.X`) is not seen either. `CORE_ROOTS` is a list of exceptions,
held from growing only by review: a new file added there with a reason is green. The bridge module
(`src/mobile/bridge`, the desktops' dispatch over every application) is not read: it is a host, a composition root
by its nature. A platform whose rules are not switched on (`PLATFORMS`) is not read at all. On Linux a `#[path]`
attribute that mounts another directory's file, an item re-exported by core under another name (`pub use`) and a
macro that expands to a path are not seen; a kind literal (`"EXPENSES_POT"`, blanked) is not either.
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
CORE_SRC = MOBILE / "core" / "src"
CORE_PACKAGE = "me.raddatz.veyq.mobile"
APPLICATIONS = CORE_SRC / "commonMain/kotlin/me/raddatz/veyq/mobile/navigation/Applications.kt"
ENTRY = re.compile(r"^\s*([A-Z][A-Z0-9_]*)\s*\(\s*\"([a-z][a-z0-9]*)\"\s*\)\s*,", re.MULTILINE)
PACKAGE_DECL = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)

# The Kotlin core's files outside the application packages that still name one (rule c2), by their path under
# `core/src/`, each with the reason. Only shrinks: an entry that names no application any more is red.
CORE_ROOTS: dict[str, str] = {
    "commonMain/kotlin/me/raddatz/veyq/mobile/AppKinds.kt":
        "the composition root: each application's part of core's tables (search, share, export, copy, re-key, pins, queued writes)",
    "commonMain/kotlin/me/raddatz/veyq/mobile/navigation/ShellModel.kt":
        "the shell's model holds each application's open screen model (gallery, file, uploads, Memory create, music, tracks, reports); a hook per screen model needs a design of its own",
    "commonMain/kotlin/me/raddatz/veyq/mobile/navigation/Drop.kt":
        "the one drop (ADR 0154 decision 1) hands a level's files to Depot's uploads and a gallery's to Memories'; behind AppKinds a design of its own",
    "commonMain/kotlin/me/raddatz/veyq/mobile/navigation/AddHere.kt":
        "where Add here goes: Depot's uploads into a level, Memories' into the open gallery; the drop's twin",
    "commonMain/kotlin/me/raddatz/veyq/mobile/navigation/ShareSheet.kt":
        "the top level's Add files only reads Depot's files and drop boxes",
    "commonMain/kotlin/me/raddatz/veyq/mobile/RowDetails.kt":
        "a row's second line read from the Pots, Depot's files and drop boxes",
    "commonMain/kotlin/me/raddatz/veyq/mobile/words/Notices.kt":
        "the notices of applications' outcomes (an upload's, a folder walk's, a Pot copy's) said in core's one table",
    "commonMain/kotlin/me/raddatz/veyq/mobile/session/LinkForm.kt":
        "a pasted link opened as a Pot or a Depot file",
    "commonMain/kotlin/me/raddatz/veyq/mobile/sharing/GuestAccess.kt":
        "GuestPage's variants carry each kind's member page model (share view = member view, ADR 0150 decision 5)",
    "commonMain/kotlin/me/raddatz/veyq/mobile/sharing/GuestView.kt":
        "a guest's gallery is handed the music and the media deriver it plays and uploads with",
    "commonMain/kotlin/me/raddatz/veyq/mobile/offline/QueuedTray.kt":
        "the tray says each queued Expenses write by its own type",
    "commonMain/kotlin/me/raddatz/veyq/mobile/offline/KeptRead.kt":
        "the kept reads' serializers are the applications' generated models (ITEM_FAVORITES, NODE_BUCKETS, POT_REVISIONS, …)",
    "nativeMain/kotlin/me/raddatz/veyq/mobile/blob/PickedPaths.kt":
        "a picked folder walked into Depot's FolderUpload.Found",
    "appleMain/kotlin/me/raddatz/veyq/mobile/sharing/AppleSharing.kt":
        "Swift's adapter (Swift calls no suspend function or flow) for a link's pages, whose models are the applications'",
    "appleMain/kotlin/me/raddatz/veyq/mobile/audio/AppleAudio.kt":
        "Swift's adapter for the music: a track's save and its WAV wrap are Memories'",
    "appleMain/kotlin/me/raddatz/veyq/mobile/export/FileDownloads.kt":
        "Swift's adapter for a Pot's export",
    "appleMain/kotlin/me/raddatz/veyq/mobile/session/Apple.kt":
        "Swift's adapter: a Pot opened, its receipts read and picked",
}

# The platforms whose UI rules (a) and (b) are on; each is switched on in its phase of cr-apps-core-ui-layer.
PLATFORMS: dict[str, bool] = {"android": True, "linux": True}

ANDROID_SRC = MOBILE / "android" / "src"
ANDROID_PACKAGE = "me.raddatz.veyq.mobile.android"
ANDROID_BASE = pathlib.Path("kotlin/me/raddatz/veyq/mobile/android")
ANDROID_ROOT = pathlib.Path("main") / ANDROID_BASE / "Applications.kt"

LINUX_SRC = repo_layout.MOBILE_LINUX / "src"
LINUX_ROOT = pathlib.Path("applications.rs")
RUST_TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|::|[{},;]|\S")


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


def core_sources(core_src: pathlib.Path) -> list[pathlib.Path]:
    out: list[pathlib.Path] = []
    for source_set in sorted(p for p in core_src.iterdir() if p.is_dir() and p.name.endswith("Main")):
        out += sorted((source_set / "kotlin").rglob("*.kt"))
    return out


def check_core(core_src: pathlib.Path, apps: list[str], roots: dict[str, str]) -> list[str]:
    errors: list[str] = []
    files = core_sources(core_src)
    if not files:
        return [f"{_rel(core_src)}: no Kotlin file under */kotlin — the guard would check nothing"]
    named = re.compile(r"\b" + _dotted(CORE_PACKAGE) + r"\s*\.\s*(?:api\s*\.\s*)?(" + "|".join(apps) + r")\b")
    seen_roots: set[str] = set()
    for f in files:
        rel = f.relative_to(core_src)
        source_set_rel = pathlib.Path(*rel.parts[2:])  # under kotlin/
        expected = ".".join(source_set_rel.parent.parts)
        masked = mask(f.read_text(encoding="utf-8"))
        declared = _package_of(masked)
        if declared != expected:
            errors.append(f"{_rel(f)}: declares package `{declared}`, its directory is `{expected}` — a package is its directory's")
            continue
        under = source_set_rel.parts[len(CORE_PACKAGE.split(".")):]
        own = under[0] if len(under) > 1 and under[0] in apps else None
        hits = [(m.group(1), m.start()) for m in named.finditer(masked)]
        key = str(rel)
        if own is not None:
            for app, at in hits:
                if app != own:
                    errors.append(
                        f"{_rel(f)}:{_line(masked, at)}: (c1) the application `{own}` names the application `{app}` — "
                        f"what two applications share belongs to core",
                    )
        elif hits:
            if key in roots:
                seen_roots.add(key)
            else:
                for app, at in hits:
                    errors.append(
                        f"{_rel(f)}:{_line(masked, at)}: (c2) core names the application `{app}` — core reaches an "
                        f"application only through AppKinds (or a CORE_ROOTS entry with its reason)",
                    )
    for key in sorted(roots):
        if not (core_src / key).is_file():
            errors.append(f"{_rel(core_src / key)}: a CORE_ROOTS entry that does not exist — remove it")
        elif key not in seen_roots:
            errors.append(f"{_rel(core_src / key)}: a CORE_ROOTS entry that names no application any more — remove it")
    return errors


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


def check_linux(linux_src: pathlib.Path, apps: list[str]) -> list[str]:
    errors: list[str] = []
    files = linux_sources(linux_src)
    if not files:
        return [f"{_rel(linux_src)}: no Rust file — the guard would check nothing"]
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


def check(
    core_src: pathlib.Path = CORE_SRC,
    applications_file: pathlib.Path = APPLICATIONS,
    roots: dict[str, str] | None = None,
    platforms: dict[str, bool] | None = None,
    android_src: pathlib.Path = ANDROID_SRC,
    linux_src: pathlib.Path = LINUX_SRC,
) -> list[str]:
    if not applications_file.is_file():
        return [f"{_rel(applications_file)}: not found — the applications are derived from ApplicationId's entries"]
    apps = applications(applications_file.read_text(encoding="utf-8"))
    if not apps:
        return [f"{_rel(applications_file)}: no ApplicationId entry read — the guard would check nothing"]
    errors = check_core(core_src, apps, CORE_ROOTS if roots is None else roots)
    on = PLATFORMS if platforms is None else platforms
    if on.get("android"):
        errors += check_android(android_src, apps)
    if on.get("linux"):
        errors += check_linux(linux_src, apps)
    return errors


def main() -> int:
    errors = check()
    for e in errors:
        print(e)
    if errors:
        print(f"validate_mobile_ui_direction: {len(errors)} violation(s)")
        return 1
    read = len(core_sources(CORE_SRC)) + (len(android_sources(ANDROID_SRC)) if PLATFORMS.get("android") else 0) \
        + (len(linux_sources(LINUX_SRC)) if PLATFORMS.get("linux") else 0)
    on = ", ".join(p for p, v in PLATFORMS.items() if v) or "none"
    print(f"validate_mobile_ui_direction: OK ({read} files; the Kotlin core, platforms on: {on})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
