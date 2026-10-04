#!/usr/bin/env python3
"""Guard that the backend's `shared/` imports no feature, and that `core/` imports no Memories application code
(developer-styleguide § 1, ADR 0007's shape, ADR 0079).

**What it asserts.** No Kotlin source under `backend/src/main/kotlin/com/veyq/app/shared/` names anything in the
`features` package by its qualified name **in code** — an `import`, or a fully-qualified reference inline
(`com.veyq.app.features.media.MediaEntity.count()`) that needs no import at all. Dependencies run feature → shared, never back: a `shared/` slice that
needs a feature's behaviour gets it through a small interface the feature implements
(`shared/auth/AccessTokenResolver`, which `SessionService` implements), and a `shared/` slice that needs a
feature's table gets the entity moved into `shared/` (`shared/tenant/TenantEntity`, `shared/scope/ScopeEntity`,
`shared/membership/MembershipEntity`), with the feature's store still its only writer. The files are found by
walking the directory, and a run that finds none is red, so a moved or renamed tree cannot pass as clean.

**Core half (ADR 0079 phase 1, step 1).** ADR 0079 makes veyq a platform and Memories its first
application, and an application depends on core, never the reverse. So no Kotlin source under `shared/` **or**
`core/` (the `com.veyq.app.core` tree, which gains `core.node`/`core.blob`/`core.sharing`/`core.quota` as the
restructuring moves code in) names `features.media`, `features.memory` or the favorites packages
(`features.content.favorites`, `features.content.itemfavorites`). `shared/` stays barred from *every* feature; `core/`
is barred from the Memories application only, because it still imports `features.content` while that package has
not moved. `core/` does not exist until step 2a: a missing directory is not an error, `shared/` alone must still
yield files.

**The defect that bought it.** No incident — built with `cr-shared-imports-no-features` (inbox #173), which
promoted its one-shot proof to this guard. The rule had been prose in the developer styleguide ("`shared/` must
not depend on a feature") with nothing reading it, and the 2026-09-23 review found four imports breaking it:
`SessionTokenIdentityProvider` on `SessionService`, and three `shared/tenant` classes on the tenant entity,
which then still lived in the admin feature. A cycle through `shared/` is the kind that grows: every feature
may depend on `shared/`, so one back-edge makes every feature reachable from every other.

**Its limit.** `features.memory` does not exist yet and is forbidden prospectively, so that half of the core rule
bites nothing today. Two things are not checked, on purpose:
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
The web counterpart of the *core half* is `validate_web_core_imports_no_applications.py` (a ratchet, with an
allowlist that only shrinks). The *shared half* has none on the web side: `shared/ui/SecretKeyField.vue` imports
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
APP = ROOT / "backend" / "src" / "main" / "kotlin" / pathlib.Path(*BASE.split("."))
SHARED = APP / "shared"
CORE = APP / "core"  # absent until ADR 0079 phase 1, step 2a creates it; absence is not an error
# Built from the package name rather than typed out, so this file never spells what it forbids. A qualified name
# anywhere in code — an import line or an inline reference — not preceded by another name part, not followed by one.
FEATURE_REF = re.compile(r"(?<![\w.])" + re.escape(BASE + ".features") + r"(?![\w])")
# The Memories application, as core may not see it. `features.memory` is forbidden prospectively.
APPLICATION = [BASE + ".features." + p for p in
               ("media", "memory", "content.favorites", "content.itemfavorites")]
APPLICATION_REF = re.compile(r"(?<![\w.])(" + "|".join(re.escape(p) for p in APPLICATION) + r")(?![\w])")


def main() -> int:
    files = sorted(SHARED.rglob("*.kt"))
    if not files:
        print(f"SHARED-IMPORTS FAILED — no Kotlin source under {SHARED.relative_to(ROOT)}; the guard checked "
              "nothing. If shared/ moved, move this guard's anchor with it.")
        return 1

    core_files = sorted(CORE.rglob("*.kt")) if CORE.is_dir() else []

    hits = []
    for tree, pattern in ((files, FEATURE_REF), (core_files, APPLICATION_REF)):
        for path in tree:
            source = path.read_text(encoding="utf-8")
            masked = mask(source).splitlines()
            for lineno, line in enumerate(source.splitlines(), start=1):
                if pattern.search(masked[lineno - 1]):
                    hits.append(f"  - {path.relative_to(ROOT)}:{lineno}: {line.strip()}")

    if hits:
        print("SHARED-IMPORTS FAILED — backend shared/ names a feature, or core/ names the Memories application, "
              "by import or fully-qualified reference (developer-styleguide § 1, ADR 0079):")
        print("\n".join(hits))
        print("Share the rule through a small interface in shared/ or core/ that the feature implements, or move "
              "the entity there with the feature's store still its only writer.")
        return 1

    print(f"shared imports OK: {len(files)} Kotlin file(s) under shared/, none names a feature in code; "
          f"{len(core_files)} under core/, none names the Memories application")
    return 0


if __name__ == "__main__":
    sys.exit(main())
