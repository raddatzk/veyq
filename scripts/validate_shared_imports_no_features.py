#!/usr/bin/env python3
"""Guard that the backend's `shared/` imports no feature, and that `core/` imports no Memories application code
(developer-styleguide § 1, ADR 0007's shape, ADR 0079).

**What it asserts.** No Kotlin source under `backend/src/main/kotlin/com/veyq/app/shared/` carries an `import`
of anything in the `features` package. Dependencies run feature → shared, never back: a `shared/` slice that
needs a feature's behaviour gets it through a small interface the feature implements
(`shared/auth/AccessTokenResolver`, which `SessionService` implements), and a `shared/` slice that needs a
feature's table gets the entity moved into `shared/` (`shared/tenant/TenantEntity`, `shared/scope/ScopeEntity`,
`shared/membership/MembershipEntity`), with the feature's store still its only writer. The files are found by
walking the directory, and a run that finds none is red, so a moved or renamed tree cannot pass as clean.

**Core half (cr-platform-core-boundaries step 1).** ADR 0079 makes veyq a platform and Memories its first
application, and an application depends on core, never the reverse. So no Kotlin source under `shared/` **or**
`core/` (the `com.veyq.app.core` tree, which gains `core.node`/`core.blob`/`core.sharing`/`core.quota` as the
restructuring moves code in) carries an `import` of `features.media`, `features.memory` or the favorites packages
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
bites nothing today. Three things are not checked, on purpose:
- **The test tree.** Shared *tests* legitimately import features — `OperatorBlindProbeTest`, the rate-limit
  tests, `SessionTokenIdentityProviderTest` (it issues a real session to resolve), `TenantIngressProjectorTest`
  — and integration tests may; a test is a consumer of both sides, not a dependency of either.
- **KDoc links.** `StoreReclaim.kt` and `StoreReclaimSweep.kt` link to feature classes by their qualified names
  in comments, which is documentation, not a dependency; only `import` lines are read.
- **A fully-qualified code reference without an import.** There were none when this guard was written (every
  qualified mention under `shared/` was one of the two KDoc links above), and telling a qualified reference in
  code from one in a comment needs a Kotlin lexer, not a line match. The compiler does not care which form a
  back-edge takes, so this is a real hole, just an unoccupied one.
The web side has no counterpart: `shared/ui/SecretKeyField.vue` imports the Emergency Kit's read half from
`features/account/`, a known inversion the ui-web styleguide names, so a web guard would be red on the day it
was born.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BASE = "com.veyq.app"
APP = ROOT / "backend" / "src" / "main" / "kotlin" / pathlib.Path(*BASE.split("."))
SHARED = APP / "shared"
CORE = APP / "core"  # absent until cr-platform-core-boundaries step 2a creates it; absence is not an error
# Built from the package name rather than typed out, so this file never spells what it forbids.
FEATURE_IMPORT = re.compile(r"^\s*import\s+" + re.escape(BASE + ".features") + r"(\.|\s|$)")
# The Memories application, as core may not see it. `features.memory` is forbidden prospectively.
APPLICATION = [BASE + ".features." + p for p in
               ("media", "memory", "content.favorites", "content.itemfavorites")]
APPLICATION_IMPORT = re.compile(r"^\s*import\s+(" + "|".join(re.escape(p) for p in APPLICATION) + r")(\.|\s|$)")


def main() -> int:
    files = sorted(SHARED.rglob("*.kt"))
    if not files:
        print(f"SHARED-IMPORTS FAILED — no Kotlin source under {SHARED.relative_to(ROOT)}; the guard checked "
              "nothing. If shared/ moved, move this guard's anchor with it.")
        return 1

    core_files = sorted(CORE.rglob("*.kt")) if CORE.is_dir() else []

    hits = []
    for tree, pattern in ((files, FEATURE_IMPORT), (core_files, APPLICATION_IMPORT)):
        for path in tree:
            for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                if pattern.match(line):
                    hits.append(f"  - {path.relative_to(ROOT)}:{lineno}: {line.strip()}")

    if hits:
        print("SHARED-IMPORTS FAILED — backend shared/ imports a feature, or core/ imports the Memories application "
              "(developer-styleguide § 1, ADR 0079):")
        print("\n".join(hits))
        print("Share the rule through a small interface in shared/ or core/ that the feature implements, or move "
              "the entity there with the feature's store still its only writer.")
        return 1

    print(f"shared imports OK: {len(files)} Kotlin file(s) under shared/, none imports a feature; "
          f"{len(core_files)} under core/, none imports the Memories application")
    return 0


if __name__ == "__main__":
    sys.exit(main())
