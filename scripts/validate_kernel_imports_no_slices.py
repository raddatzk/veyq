#!/usr/bin/env python3
"""Guard the backend's two import directions: the platform kernel names no feature slice, and no platform file
names Memories application code (developer-styleguide § 1, ADR 0007's shape, ADR 0079).

**What it asserts.** Over every hand-written main Kotlin file (every `src/main/kotlin` under `backend/`, found by
`backend_tree`), read through the Kotlin lexer so comments and string contents do not count:

- **Kernel rule.** `KERNEL` below is the declared list of the platform's kernel packages — what every feature slice
  builds on (tenant resolution, identity, auth, membership, the blob store, rate limits, …). Every other package a
  main file declares is a **slice**: a new package is a slice by default, and joining the kernel is an edit to this
  list, made on purpose. No file whose `package` is a kernel package (or below one) names a slice package by its
  qualified name **in code** — an `import`, or a fully-qualified reference inline that needs no import at all. A
  name's package is the longest package some main file declares that prefixes it, so a class of the generated API
  (declared by no hand-written file) is neither kernel nor slice and stays usable. Dependencies run slice → kernel,
  never back: a kernel package that needs a slice's behaviour gets it through a small interface the slice implements
  (`com.veyq.core.auth.AccessTokenResolver`, which `SessionService` implements), and one that needs a slice's
  table gets the entity moved into the kernel (`com.veyq.core.tenant.TenantEntity`,
  `com.veyq.core.scope.ScopeEntity`, `com.veyq.core.membership.MembershipEntity`) with the slice's
  store still its only writer. Every `KERNEL` entry must be declared by some main file, so the list cannot rot into
  names that guard nothing, and a run in which no file is kernel is red.
- **Platform rule (ADR 0079).** veyq is a platform and Memories its first application; an application depends on
  core, never the reverse. So no main file **whose `package` is not a Memories package** names a Memories package
  (`APPLICATION`) in code. The platform is derived as the inverse of the application — every main file that is not
  Memories' — rather than listed, so a new platform package is covered the day it appears; it is the cut the Gradle
  module split makes (`:core` cannot see `:apps:memories`). A run in which no file declares a Memories package is
  red too: the application list would then name nothing, and every platform file would pass by default.

**Why a declared list (owner's decision, 2026-10-04, `cr-platform-gradle-modules` open question 3).** The layering
was once a directory: the kernel lived under `shared/`, the slices under `features/`, and the guard barred
`shared/` from naming `features`. The package rename of that CR folds both trees into `com.veyq.core.<feature>`,
which leaves the layering no name to hang on; the 15 former `shared/` packages became this list instead of a
`core.shared` sub-tree whose name says nothing the module does not.

**The defect that bought it.** No incident — built with `cr-shared-imports-no-features` (inbox #173), which
promoted its one-shot proof to this guard. The rule had been prose in the developer styleguide ("`shared/` must
not depend on a feature") with nothing reading it, and the 2026-09-23 review found four imports breaking it:
`SessionTokenIdentityProvider` on `SessionService`, and three tenant-kernel classes on the tenant entity, which then
still lived in the admin feature. A cycle through the kernel is the kind that grows: every slice may depend on the
kernel, so one back-edge makes every slice reachable from every other. **What widened the platform rule:** phase 1
barred only two directories, and `features.spaces.ScopesStore` imported the item-favorites entity to clear a
removed member's favorites — a platform → Memories edge the guard could not see. The inverse derivation was red on
exactly that line on the untouched tree (2026-10-04, `cr-platform-gradle-modules` step 0); the edge is now the core
`MembershipEraser` that item favorites implements.

**Its limit.** Membership is read from the file's `package` line, not its directory: Kotlin allows a package that
differs from the path, and a file that declares a Memories or kernel package is treated as one wherever it sits
(the module split makes the path the boundary for the platform rule; the kernel stays a declaration). A slice
importing another slice is not this guard's subject — slices may use each other. Two things are not checked, on
purpose:
- **The test tree.** Kernel *tests* legitimately import slices — `OperatorBlindProbeTest`, the rate-limit tests,
  `SessionTokenIdentityProviderTest` (it issues a real session to resolve), `TenantIngressProjectorTest` — and
  integration tests may; a test is a consumer of both sides, not a dependency of either.
- **Comments and string literals.** Each file is read through the Kotlin lexer `validate_coverage` uses too
  (`kotlin_lexer.mask`), which blanks comments and literal contents at unchanged offsets, so a KDoc link to a slice
  class by its qualified name is documentation, not a dependency, and is not read. The other side of the same cut:
  a qualified name inside a **string** (`Class.forName("…")`) is a real runtime back-edge and is masked away with
  the rest. None exists; reflection by name is not a shape this codebase uses. Before the lexer, only `import`
  lines were read and an inline qualified reference was green (2026-10-04 review; pinned rejected by
  `validate_guard_gaps` case 38).
The web counterpart of the *platform rule* is `validate_web_core_imports_no_applications.py` (no allowlist: any
violation is red). The *kernel rule* has none on the web side: `shared/ui/SecretKeyField.vue` imports the Emergency
Kit's read half from `features/account/`, a known inversion the ui-web styleguide names, so a web kernel → slice
guard would be red on the day it was born.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here
from kotlin_lexer import mask  # the one Kotlin lexer in scripts/: comments + literals blanked, offsets kept

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The kernel, declared. Each entry covers the package and every package below it. Written out in full, one per
# line, so a package rename reaches each entry like any other qualified name.
KERNEL = [
    "com.veyq.core.adminchannel",
    "com.veyq.core.appshell",
    "com.veyq.core.auth",
    "com.veyq.core.blobstore",
    "com.veyq.core.config",
    "com.veyq.core.crypto",
    "com.veyq.core.http",
    "com.veyq.core.identity",
    "com.veyq.core.instance",
    "com.veyq.core.membership",
    "com.veyq.core.persistence",
    "com.veyq.core.ratelimit",
    "com.veyq.core.scope",
    "com.veyq.core.tenant",
    "com.veyq.core.validation",
]
# The Memories application, as the platform may not see it: its whole namespace, so a new Memories package is
# covered the day it appears.
APPLICATION = [
    "com.veyq.memories",
]
# A qualified name in code, not preceded by another name part. Its package is resolved against the declared ones.
QUALIFIED = re.compile(r"(?<![\w.])com\.veyq(?:\.\w+)+")
APPLICATION_REF = re.compile(r"(?<![\w.])(" + "|".join(re.escape(p) for p in APPLICATION) + r")(?![\w])")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def under(package: str | None, roots: list[str]) -> bool:
    return package is not None and any(package == p or package.startswith(p + ".") for p in roots)


def package_of(name: str, declared: set[str]) -> str | None:
    """The longest declared package that `name` lies in (on a segment boundary), or None."""
    parts = name.split(".")
    for n in range(len(parts), 0, -1):
        candidate = ".".join(parts[:n])
        if candidate in declared:
            return candidate
    return None


def main() -> int:
    files = backend_tree.main_kotlin()
    if not files:
        print(f"KERNEL-IMPORTS FAILED — no Kotlin under {backend_tree.BACKEND.relative_to(ROOT)}/**/src/main/kotlin; "
              "the guard checked nothing.")
        return 1

    read: list[tuple[pathlib.Path, str | None, list[str], list[str]]] = []
    for path in files:
        source = path.read_text(encoding="utf-8")
        masked = mask(source)
        m = PACKAGE.search(masked)
        read.append((path, m.group(1) if m else None, source.splitlines(), masked.splitlines()))
    declared = {pkg for _, pkg, _, _ in read if pkg}

    errors: list[str] = []
    stale = [k for k in KERNEL if not any(under(pkg, [k]) for pkg in declared)]
    if stale:
        errors.append("KERNEL names packages no main file declares — the list has rotted or the package moved: "
                      + ", ".join(stale))
    kernel = [r for r in read if under(r[1], KERNEL)]
    platform = [r for r in read if not under(r[1], APPLICATION)]
    application_files = len(read) - len(platform)
    if not kernel:
        errors.append("no main Kotlin file declares a KERNEL package; the kernel rule would pass every file by "
                      "default. If the kernel moved, move KERNEL with it.")
    if not application_files:
        errors.append("no main Kotlin file declares a Memories package "
                      f"({', '.join(APPLICATION)}); the platform rule would pass every file by default. If Memories "
                      "moved, move APPLICATION with it.")
    if errors:
        print("KERNEL-IMPORTS FAILED —")
        print("\n".join(f"  - {e}" for e in errors))
        return 1

    hits: dict[tuple[pathlib.Path, int], str] = {}
    for path, _, source, masked in kernel:
        for lineno, line in enumerate(masked, start=1):
            for m in QUALIFIED.finditer(line):
                target = package_of(m.group(0), declared)
                if target is not None and not under(target, KERNEL):
                    hits[(path, lineno)] = (f"  - {path.relative_to(ROOT)}:{lineno}: kernel names slice {target}: "
                                            f"{source[lineno - 1].strip()}")
    for path, _, source, masked in platform:
        for lineno, line in enumerate(masked, start=1):
            if APPLICATION_REF.search(line):
                hits.setdefault((path, lineno), f"  - {path.relative_to(ROOT)}:{lineno}: platform names the Memories "
                                                f"application: {source[lineno - 1].strip()}")

    if hits:
        print("KERNEL-IMPORTS FAILED — a kernel package names a slice, or a platform file names the Memories "
              "application, by import or fully-qualified reference (developer-styleguide § 1, ADR 0079):")
        print("\n".join(hits[k] for k in sorted(hits)))
        print("Share the rule through a small interface in the kernel that the slice implements, or move the entity "
              "into the kernel with the slice's store still its only writer.")
        return 1

    print(f"kernel imports OK: {len(kernel)} kernel file(s) in {len(KERNEL)} declared package(s), none names one of "
          f"the {len(declared - {p for p in declared if under(p, KERNEL)})} slice package(s) in code; "
          f"{len(platform)} platform file(s) (every main file outside the {application_files} of Memories' packages), "
          "none names the Memories application")
    return 0


if __name__ == "__main__":
    sys.exit(main())
