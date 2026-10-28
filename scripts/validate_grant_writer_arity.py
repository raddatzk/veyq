#!/usr/bin/env python3
"""Guard that every write of a grant's granter also records the key that granter signed it with (ADR 0028).

**What it asserts.** A grant row names its granter (`granterUserId`) and carries a copy of that granter's identity
public bundle as it was when the grant was written (`granterIdentityPublicKey`); the reads serve the copy. So:

  - *pairing* — every function of a backend `*Store.kt` that assigns `granterUserId` (an entity property or a JPQL
    `set` clause) assigns `granterIdentityPublicKey` in the same function body;
  - *arity* — the writers are exactly the ten named in `EXPECTED_WRITERS`, each with one site. An eleventh writer turns
    this red even when it records the copy, because it also owes a leg in `GrantWriterSnapshotSliceTest`, the test
    that proves each writer's copy is the granter's key at the moment of the write. Name it here when the leg exists.

**The defect that bought it.** Inbox #233: the reads served the granter's *current* key, so an account rotation
(ADR 0060) broke every grant the rotator had signed — the recipient verifies before it reads its pin, and fails the
signature as `bad_signature`. An owner holding such a grant could not open the scope to complete the very rotation
that would have replaced it. The copy fixes that only where it is written: a writer that sets the granter and not
the copy serves no key at all, which the recipient refuses as unsigned — the lock back, for that writer's grants.

**Its limits.**

  (a) It reads **spellings**. A granter written under another name — a native SQL `granter_user_id =`, a setter
      call, a copy of a whole row — escapes the pairing key; the native SQL form is matched too, the rest are not.
  (b) It proves the copy is **assigned** in the function, not that it is assigned the granter's key, on the right
      row, from the right transaction. `GrantWriterSnapshotSliceTest` proves the value, one leg per writer.
  (c) The function body runs from the `fun` line to the class member's closing brace at four spaces, so a writer
      that is not a class member (a top-level function, a nested class) is still found, but its body may be read
      short or long.
  (d) It reads `*Store.kt` only, where persistence lives by convention (ADR 0001). The resources map the read rows
      onto generated response models with the same property names (`granterUserId = …` as a named argument), which
      no grep tells apart from an entity write; a grant written from outside a store escapes this guard.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent

# An assignment, not a comparison: `granterUserId = x`, `it.granterUserId = x`, JPQL `granterUserId = ?5`, and the
# native SQL column — never `granterUserId == x` or a read on the right-hand side.
GRANTER_WRITE = re.compile(r"\b(?:granterUserId|granter_user_id)\s*=(?!=)")
SNAPSHOT_WRITE = re.compile(r"\b(?:granterIdentityPublicKey|granter_identity_public_key)\s*=(?!=)")
FUN = re.compile(r"^\s*(?:(?:private|internal|protected|public|override|open|suspend)\s+)*fun\s+(?:<[^>]*>\s*)?(\w+)")

EXPECTED_WRITERS = {
    "ScopesStore.createPersonal": 1,  # a member's personal scope birth (self-grant)
    "ScopesStore.createGroup": 1,  # a group's birth (self-grant)
    "ScopesStore.rotate": 1,  # a scope rotation's re-grants (foreign and self)
    "TasksStore.complete": 1,  # a grant-delivery completion (foreign)
    "TenantStore.activate": 1,  # the tenant's cold birth (self-grant)
    "TenantStore.mintMemberInvite": 1,  # a member invite's grant to its invite key (the inviting owner, ADR 0111)
    "TenantStore.joinAsMember": 1,  # a member invite's accept, sign-in and register alike (the newcomer's self-grant)
    "TenantStore.carryInvites": 1,  # a tenant rotation's re-grant of each open invite it carries (the rotator)
    "ScopesStore.addMember": 1,  # a group add, direct or a consent proposal (the adding owner, ADR 0111 decision 4)
    "RotationStore.applySelfGrant": 1,  # a secured account's staged self-grant, applied by its publish (cr-secure-the-account)
}


def writer_sites() -> tuple[dict[str, int], list[str]]:
    """Every function assigning a granter → its site count, plus each such function lacking the snapshot."""
    sites: dict[str, int] = {}
    unpaired: list[str] = []
    for path in backend_tree.main_kotlin("*Store.kt"):
        lines = path.read_text(encoding="utf-8").splitlines()
        for start, line in enumerate(lines):
            fun = FUN.match(line)
            if not fun:
                continue
            body = [line]
            for following in lines[start + 1:]:
                if FUN.match(following) and not following.startswith("        "):
                    break
                body.append(following)
                if following == "    }":
                    break
            code = [b for b in body if not b.lstrip().startswith(("//", "*", "/*"))]
            count = sum(len(GRANTER_WRITE.findall(b)) for b in code)
            if not count:
                continue
            name = f"{path.stem}.{fun.group(1)}"
            sites[name] = sites.get(name, 0) + count
            if not any(SNAPSHOT_WRITE.search(b) for b in code):
                unpaired.append(f"{path.relative_to(ROOT)}: {name}")
    return sites, unpaired


def main() -> int:
    if not backend_tree.main_roots():
        print(
            "GRANT WRITER ARITY VALIDATION FAILED:\n"
            f"  - missing tree: no {backend_tree.BACKEND}/**/src/main/kotlin. This guard reads backend sources directly; a moved tree must be "
            f"re-pointed, not silently skipped."
        )
        return 1

    sites, unpaired = writer_sites()
    errors: list[str] = []
    for where in unpaired:
        errors.append(
            f"pairing: {where} writes a grant's granter but not `granterIdentityPublicKey`. The reads serve that copy, "
            f"so the grant is served with no granter key and every recipient refuses it as unsigned (ADR 0028 "
            f"§ What is still open). Record the granter's identity key in the same write."
        )
    for name in sorted(set(sites) - set(EXPECTED_WRITERS)):
        errors.append(
            f"arity: `{name}` is a new grant writer. Give it a leg in GrantWriterSnapshotSliceTest (its copy equals the "
            f"granter's key at the write), then name it in EXPECTED_WRITERS."
        )
    for name in sorted(set(EXPECTED_WRITERS) - set(sites)):
        errors.append(
            f"arity: `{name}` no longer writes a granter. If the writer was genuinely removed or renamed, update "
            f"EXPECTED_WRITERS and its GrantWriterSnapshotSliceTest leg; a guard that goes quiet because its subject "
            f"moved proves nothing."
        )
    for name in sorted(set(sites) & set(EXPECTED_WRITERS)):
        if sites[name] != EXPECTED_WRITERS[name]:
            errors.append(f"arity: `{name}` writes a granter at {sites[name]} sites, expected {EXPECTED_WRITERS[name]}.")

    if errors:
        print("GRANT WRITER ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(EXPECTED_WRITERS)} grant writers, each recording its granter's identity key beside the granter.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
