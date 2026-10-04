#!/usr/bin/env python3
"""Guard that backend tests create account rows through the one test fixture, never through a local copy.

**What it asserts.** Under `backend/src/test`, the account entity — the `@Entity` mapped to the `veyq_user`
table, found in `backend/src/main` by that table name rather than typed here — is constructed in exactly one
file, `com/veyq/core/testing/TestFixtures.kt`, and that file does construct it. Every other test seeds an
account through the fixture's `seedUser` / `newUser` / `ensureUser`
(developer-styleguide §10: *test data comes from one fixture per surface, not a local copy*).

**The defect that bought it.** Every slice test used to carry its own `seedUser`: 50 copies when
cr-backend-test-fixtures was refined, 66 files constructing the entity when it was implemented. The cost was
concrete — a new `NOT NULL` column on `veyq_user` meant editing every one of those files, and a copy that was
missed failed only at run time, as a constraint violation inside whichever test happened to seed through it.
The copies had also drifted: two families of key blobs, a dozen email prefixes, three shapes of the same
idempotent seed. That CR folded them into the fixture; nothing but this file stops the 67th copy.

**Why the entity is found and not named.** `changes/README.md` § *a guard derives its anchor set from the code*.
The table name is the schema's identity for an account and survives a class rename; the class name is read from
the entity that maps it, and the construction pattern is built from that name.

**Its limit.** It sees the account entity only, and only a constructor call. Invisible to it:
  - a copied `seedNode`, `seedTenant` or `seedMemberScope` — a local node or tenant seed is not caught;
  - a local `bearer` / `token` helper minting its own `Authorization` header;
  - an account inserted by SQL string, or built by reflection;
  - anything outside `backend/src/test` — production code constructs accounts legitimately.
Those copies are not guarded on purpose: a schema change to a node, tenant or session costs only the file that
holds the copy, which the compiler or the first run names. The account row is the one every slice test seeds,
which is why its copies were the measured cost. Green means exactly: **one test file constructs the account
entity, and it is the fixture.**
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
HOME = backend_tree.test_file("com.veyq.core.testing.TestFixtures")
TABLE = "veyq_user"

# `@Table(name = "<table>")` followed by the class it annotates.
ENTITY_OF = re.compile(r'@Table\(\s*name\s*=\s*"%s"\s*\)\s*(?:@\w+(?:\([^)]*\))?\s*)*class\s+(\w+)' % re.escape(TABLE))


def rel(path: pathlib.Path) -> str:
    """A repo-relative path for the error message; absolute is unreadable in CI output."""
    try:
        return str(path.resolve().relative_to(ROOT))
    except ValueError:
        return str(path)


def entity_name() -> str | None:
    names = {m.group(1) for p in backend_tree.main_kotlin() for m in ENTITY_OF.finditer(p.read_text(encoding="utf-8"))}
    return names.pop() if len(names) == 1 else None


def main() -> int:
    for what, roots in (("main", backend_tree.main_roots()), ("test", backend_tree.test_roots())):
        if not roots:
            print(f"TEST FIXTURE HOME VALIDATION FAILED:\n  - missing source tree: no "
                  f"{rel(backend_tree.BACKEND)}/**/src/{what}/kotlin")
            return 1
    name = entity_name()
    if name is None:
        print(
            f"TEST FIXTURE HOME VALIDATION FAILED:\n  - no single entity in {rel(MAIN)} maps the table "
            f"{TABLE!r}. If the account table was renamed, point this guard at the new name — do not delete it."
        )
        return 1

    # A constructor call, bare or fully qualified: `Name()` / `com.veyq….Name()`.
    construct = re.compile(r"(?<![\w])(?:[a-z]\w*\.)*%s\s*\(\s*\)" % re.escape(name))
    hits = {
        p: len(construct.findall(p.read_text(encoding="utf-8")))
        for p in backend_tree.test_kotlin()
    }
    copies = [p for p, n in hits.items() if n and p.resolve() != HOME.resolve()]

    errors: list[str] = []
    if not HOME.is_file():
        errors.append(f"the fixture {rel(HOME)} is missing — it is the one home of the backend's seeded test data.")
    elif not hits.get(HOME):
        errors.append(
            f"{rel(HOME)} no longer constructs {name}. The fixture is where an account row is built; if it moved, "
            f"point HOME at the new file."
        )
    for p in copies:
        errors.append(
            f"{rel(p)} constructs {name} itself ({hits[p]}x). Seed the account through the fixture — "
            f"`seedUser(...)`, or `newUser(...)` where the row must join the test's own transaction — so a new "
            f"column on {TABLE} is one edit, not one per test file."
        )

    if errors:
        print("TEST FIXTURE HOME VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {name} (table {TABLE}) is constructed in {rel(HOME)} only, among {len(hits)} backend test files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
