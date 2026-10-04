#!/usr/bin/env python3
"""Guard that the backend's `shared/` imports no feature, and that no platform file imports Memories application
code (developer-styleguide § 1, ADR 0007's shape, ADR 0079).

**What it asserts.** No Kotlin source under `backend/src/main/kotlin/com/veyq/app/shared/` names anything in the
`features` package by its qualified name **in code** — an `import`, or a fully-qualified reference inline
(`com.veyq.app.features.media.MediaEntity.count()`) that needs no import at all. Dependencies run feature → shared, never back: a `shared/` slice that
needs a feature's behaviour gets it through a small interface the feature implements
(`shared/auth/AccessTokenResolver`, which `SessionService` implements), and a `shared/` slice that needs a
feature's table gets the entity moved into `shared/` (`shared/tenant/TenantEntity`, `shared/scope/ScopeEntity`,
`shared/membership/MembershipEntity`), with the feature's store still its only writer. The files are found by
walking the directory, and a run that finds none is red, so a moved or renamed tree cannot pass as clean.

**Platform half (ADR 0079).** ADR 0079 makes veyq a platform and Memories its first application, and an
application depends on core, never the reverse. So no main Kotlin source **whose `package` line is not a Memories
package** names `features.media`, `features.memory` or the favorites packages (`features.content.favorites`,
`features.content.itemfavorites`) in code. The platform is derived as the inverse of the application — every main
file that is not Memories' — rather than listed, so a new platform package is covered the day it appears and there
is no list of platform packages to rot; it is the cut the Gradle module split will make (`:core` cannot see
`:apps:memories`). `shared/` stays barred from *every* feature. A run in which no file declares a Memories package is
red too: the application list would then name nothing, and every platform file would pass by default.

**The defect that bought it.** No incident — built with `cr-shared-imports-no-features` (inbox #173), which
promoted its one-shot proof to this guard. The rule had been prose in the developer styleguide ("`shared/` must
not depend on a feature") with nothing reading it, and the 2026-09-23 review found four imports breaking it:
`SessionTokenIdentityProvider` on `SessionService`, and three `shared/tenant` classes on the tenant entity,
which then still lived in the admin feature. A cycle through `shared/` is the kind that grows: every feature
may depend on `shared/`, so one back-edge makes every feature reachable from every other.

**What widened it.** Phase 1 barred only `core/` and `shared/`, by directory, and `features.spaces.ScopesStore`
imported the item-favorites entity to clear a removed member's favorites — a platform → Memories edge the guard
could not see because `features/` was out of its scope. The inverse derivation was red on exactly that line on the
untouched tree (2026-10-04, `cr-platform-gradle-modules` step 0); the edge is now the core `MembershipEraser` that
item favorites implements.

**Its limit.** `features.memory` does not exist yet and is forbidden prospectively, so that name bites nothing
today. The exemption is read from the file's `package` line, not its directory: a file anywhere that declares a
Memories package is treated as Memories (Kotlin allows a package that differs from the path). The module split
makes the path the boundary. Two things are not checked, on purpose:
- **The test tree.** Shared *tests* legitimately import features — `OperatorBlindProbeTest`, the rate-limit
  tests, `SessionTokenIdentityProviderTest` (it issues a real session to resolve), `TenantIngressProjectorTest`
  — and integration tests may; a test is a consumer of both sides, not a dependency of either.
- **Comments and string literals.** Each file is read through the Kotlin lexer `validate_coverage` uses too
  (`kotlin_lexer.mask`), which blanks comments and literal contents at unchanged offsets, so a KDoc link to a
  feature class by its qualified name is documentation, not a dependency, and is not read (one remains,
  `shared/blobstore/StoreReclaim.kt`'s link to `ScopePurge`, measured 2026-10-04; the others were reworded
  without the package name).
  The other side of the same cut: a qualified name inside a **string** (`Class.forName("com.veyq.app.features…")`)
  is a real runtime back-edge and is masked away with the rest. None exists; reflection by name is not a
  shape this codebase uses. Before the lexer, only `import` lines were read and an inline qualified reference was
  green (2026-10-04 review; pinned rejected by `validate_guard_gaps` case 38).
The web counterpart of the *platform half* is `validate_web_core_imports_no_applications.py` (no allowlist: any violation is
red). The *shared half* has none on the web side: `shared/ui/SecretKeyField.vue` imports
the Emergency Kit's read half from `features/account/`, a known inversion the ui-web styleguide names, so a web
`shared/` → features guard would be red on the day it was born.
"""
from __future__ import annotations

import pathlib
import re
import sys

from kotlin_lexer import mask  # the one Kotlin lexer in scripts/: comments + literals blanked, offsets kept

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = "com.veyq.app"
MAIN = ROOT / "backend" / "src" / "main" / "kotlin"
APP = MAIN / pathlib.Path(*BASE.split("."))
SHARED = APP / "shared"
# Built from the package name rather than typed out, so this file never spells what it forbids. A qualified name
# anywhere in code — an import line or an inline reference — not preceded by another name part, not followed by one.
FEATURE_REF = re.compile(r"(?<![\w.])" + re.escape(BASE + ".features") + r"(?![\w])")
# The Memories application, as the platform may not see it. `features.memory` is forbidden prospectively.
APPLICATION = [BASE + ".features." + p for p in
               ("media", "memory", "content.favorites", "content.itemfavorites")]
APPLICATION_REF = re.compile(r"(?<![\w.])(" + "|".join(re.escape(p) for p in APPLICATION) + r")(?![\w])")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def is_application(package: str | None) -> bool:
    return package is not None and any(package == p or package.startswith(p + ".") for p in APPLICATION)


def main() -> int:
    files = sorted(SHARED.rglob("*.kt"))
    if not files:
        print(f"SHARED-IMPORTS FAILED — no Kotlin source under {SHARED.relative_to(ROOT)}; the guard checked "
              "nothing. If shared/ moved, move this guard's anchor with it.")
        return 1

    platform: list[tuple[pathlib.Path, str, list[str]]] = []
    application_files = 0
    masked_of: dict[pathlib.Path, tuple[str, list[str]]] = {}
    for path in sorted(MAIN.rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        masked = mask(source)
        masked_of[path] = (source, masked.splitlines())
        m = PACKAGE.search(masked)
        if is_application(m.group(1) if m else None):
            application_files += 1
        else:
            platform.append((path, source, masked.splitlines()))
    if not application_files:
        print("SHARED-IMPORTS FAILED — no main Kotlin file declares a Memories package "
              f"({', '.join(APPLICATION)}); the platform rule would pass every file by default. If Memories moved, "
              "move APPLICATION with it.")
        return 1

    hits = {}
    for tree, pattern in (([(p, *masked_of[p]) for p in files], FEATURE_REF), (platform, APPLICATION_REF)):
        for path, source, masked in tree:
            for lineno, line in enumerate(source.splitlines(), start=1):
                if pattern.search(masked[lineno - 1]):
                    hits[(path, lineno)] = f"  - {path.relative_to(ROOT)}:{lineno}: {line.strip()}"

    if hits:
        print("SHARED-IMPORTS FAILED — backend shared/ names a feature, or a platform file names the Memories "
              "application, by import or fully-qualified reference (developer-styleguide § 1, ADR 0079):")
        print("\n".join(hits[k] for k in sorted(hits)))
        print("Share the rule through a small interface in shared/ or core/ that the feature implements, or move "
              "the entity there with the feature's store still its only writer.")
        return 1

    print(f"shared imports OK: {len(files)} Kotlin file(s) under shared/, none names a feature in code; "
          f"{len(platform)} platform file(s) (every main file outside the {application_files} of Memories' "
          "packages), none names the Memories application")
    return 0


if __name__ == "__main__":
    sys.exit(main())
