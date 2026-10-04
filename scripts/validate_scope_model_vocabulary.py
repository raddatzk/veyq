#!/usr/bin/env python3
"""Guard the scope model's vocabulary — docs name the model the code actually implements (cr-0166, cr-0168).

Three phantoms had drifted into `docs/`: an `ADMIN` role, an asymmetric scope keypair, and an `ACTIVE`
membership state. All three were worse than a gap — a reader who *checked* was confirmed in the wrong belief.
cr-0166 corrected the first two, cr-0168 the third; this guard is why none can silently return.

Unlike cr-0162/0163 — where the defect was a *condition* inside a query, and only a probe test could hold it —
this invariant **is** vocabulary: certain nouns must not appear in `docs/` because the things they name do not
exist. A grep expresses it exactly, so a grep is the guard.

Four invariants:

1. **No scope keypair.** `K_SC` is a forward chain of random 32-byte content-key epochs (ADR 0024); a grant is
   `sealTo({ purpose: "scope-grant", scopeId, epoch }, memberIdentityPub, E_latest)` — asymmetric *transport* of
   a **symmetric** payload, pinned at 32 bytes by `contracts/crypto/vectors/v1.json` (`seal.asym.scope-grant.plaintext`). So a "scope
   private/public key", a "scope keypair", and a `wrappedScopePrivateKey` field are all phantoms. The phrases
   have **no** legitimate use — the *identity* keypair and the *invite-wrapping* keypair are both real and both
   named differently — so this is a flat zero-hit check with no allowlist.

2. **No `ADMIN` role.** The vocabulary is `OWNER` / `MEMBER` at every level (Server, Tenant, Group — ADR 0007,
   ADR 0014). The one legitimate use is the **negative** form ("no `ADMIN`"), which is precisely the signpost a
   future reader greps for — so that, and only that, is allowed. Rewording those ADRs to make this grep simpler
   would destroy the signpost and is not an acceptable fix for a red here.

3. **No `ACTIVE` membership state.** Four documents said server authorization required "a valid grant + `ACTIVE`
   membership". There is no membership status column and never has been — `MembershipEntity` is
   `(userId, scopeId, tenantId, role, wrappedGrant)`, and a membership **is** the row's existence. The real gate
   is the membership boundary first, then the key-holder check (ADR 0042, cc.security.authorization).

   **The phantom is `ACTIVE` applied to a *membership*, never the word itself** — and that distinction is the
   whole check. `ACTIVE` is a live state on two other axes: the **tenant** lifecycle (`PENDING → ACTIVE`,
   ADR 0006) and the **invite** lifecycle (`ACTIVE → SUPERSEDED / CONSUMED / EXPIRED`), 8 legitimate lines across
   4 files at cr-0168. A flat check on the word reds every one of them and would push someone to reword the real
   tenant lifecycle to satisfy a grep — the same failure mode invariant 2's signpost carve-out exists to avoid.
   So this keys on `ACTIVE` sitting **adjacent** to a membership word, with only whitespace/emphasis between.

   **Line-scope is a proxy for clause-scope.** The
   line this was written for carried both a phantom and a legitimate state at once —
   `owner = ACTIVE tenant-owner; tenant = ACTIVE` (`uc.admin.create-tenant.md`, a diagram step) — so a line
   cannot be judged as a whole. Two costs, both real and accepted: a phantom phrased without any of these words
   is invisible here, and a line that legitimately puts a lifecycle `ACTIVE` next to a membership word reds
   falsely. **The fix for a false positive is to split the claim, not to weaken the check.**

4. **The role constants match the contract.** `MembershipEntity`'s role constants equal the role enum in
   `contracts/api/scopes.openapi.yaml`. This is what makes cr-0166's deletion of the dead `const val ADMIN`
   stick: it fails the day a third constant returns, and it needs no allowlist either.

Scope note: the vocabulary checks read `docs/` **state** only and skip `docs/inbox.md`, which is *flow* — raw
topics that may quote the old wording while pending (docs/ai/README.md, state vs. flow). Every cr-0166 and
cr-0168 confirm-command excluded it too.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
DOCS_SKIP = {DOCS / "inbox.md"}

ENTITY = backend_tree.source_file("com.veyq.core.membership.MembershipEntity")
CONTRACT = ROOT / "contracts" / "api" / "scopes.openapi.yaml"
ROLE_SCHEMA = "SetMemberRoleRequest"

# The cr-0166 inventory grep, promoted verbatim to a permanent check.
SCOPE_ASYM = re.compile(
    r"scope keypair|scope key pair|scope-keypair|scope private key|scope public key|wrappedScopePrivateKey",
    re.IGNORECASE)

ADMIN_MENTION = re.compile(r"`ADMIN`")
ADMIN_NEGATED = re.compile(r"no\s+`ADMIN`")

# The cr-0168 inventory grep, tightened to adjacency. `ACTIVE` stays case-sensitive: it is a state literal, and
# the docs write states uppercase — lowercase "active members" is ordinary English, not the phantom. Only
# spaces/emphasis may sit between the two words; that tightness is exactly what keeps `tenant = ACTIVE` and
# `PENDING → ACTIVE` green while `ACTIVE tenant-owner` reds.
MEMBERSHIP_WORD = r"[Mm]ember(?:ship)?s?|[Tt]enant-owners?"
ACTIVE_MEMBERSHIP = re.compile(
    rf"`?ACTIVE`?[ *_]*(?:{MEMBERSHIP_WORD})"
    rf"|(?:{MEMBERSHIP_WORD})[ *_]*(?:is |as )?`?ACTIVE`?")

# The role constants in the entity's companion. The file is the (user, scope, role, grant) entity and holds no
# other `const val` — if that ever changes, this reds loudly rather than silently reading the wrong set.
KOTLIN_CONST = re.compile(r'const val (\w+)\s*=\s*"([^"]*)"')

errors: list[str] = []


def docs_files() -> list[pathlib.Path]:
    return [p for p in sorted(DOCS.rglob("*.md")) if p not in DOCS_SKIP]


def check_vocabulary(paths: list[pathlib.Path]) -> None:
    for path in paths:
        rel = path.relative_to(ROOT)
        for lineno, line in enumerate(path.read_text().splitlines(), start=1):
            hit = SCOPE_ASYM.search(line)
            if hit:
                errors.append(
                    f'{rel}:{lineno} says "{hit.group(0)}" — the scope key is **symmetric** (a 32-byte '
                    f"content-key epoch, ADR 0024) and a grant is asymmetric transport of it. Name `K_SC` / the "
                    f"grant / `wrappedGrant`; if you mean a real keypair, it is the *identity* or the "
                    f"*invite-wrapping* one")
            if ADMIN_MENTION.search(line) and not ADMIN_NEGATED.search(line):
                errors.append(
                    f"{rel}:{lineno} names an `ADMIN` role — the vocabulary is `OWNER` / `MEMBER` at every "
                    f'level (ADR 0007). Only the negative form ("no `ADMIN`") is legitimate')
            hit = ACTIVE_MEMBERSHIP.search(line)
            if hit:
                errors.append(
                    f'{rel}:{lineno} says "{hit.group(0)}" — there is no membership status column; a membership '
                    f"**is** the row's existence. Server authz is the membership boundary first, then the "
                    f"key-holder check (ADR 0042, cc.security.authorization). If this line means the *tenant* "
                    f"(`PENDING → ACTIVE`) or *invite* lifecycle — both really do have an `ACTIVE` state — then "
                    f"split that claim onto its own line; do not weaken this check")


def contract_roles() -> set[str] | None:
    schema = yaml.safe_load(CONTRACT.read_text()).get("components", {}).get("schemas", {}).get(ROLE_SCHEMA, {})
    enum = schema.get("properties", {}).get("role", {}).get("enum")
    if not enum:
        errors.append(
            f"{CONTRACT.relative_to(ROOT)} has no {ROLE_SCHEMA}.properties.role.enum — this guard's contract "
            f"anchor is gone; the role enum moved or broke")
        return None
    return set(enum)


def entity_roles() -> set[str] | None:
    consts = KOTLIN_CONST.findall(ENTITY.read_text())
    if not consts:
        errors.append(
            f"{ENTITY.relative_to(ROOT)} declares no `const val` role constants — this guard's code anchor is "
            f"gone; the role vocabulary moved")
        return None
    return {value for _name, value in consts}


def main() -> int:
    for anchor in (DOCS, ENTITY, CONTRACT):
        if not anchor.exists():
            print(f"SCOPE MODEL VOCABULARY VALIDATION FAILED:\n  - missing {anchor.relative_to(ROOT)}")
            return 1

    paths = docs_files()
    check_vocabulary(paths)

    contract, entity = contract_roles(), entity_roles()
    if contract is not None and entity is not None and contract != entity:
        errors.append(
            f"role vocabulary drift: {ENTITY.relative_to(ROOT)} has {sorted(entity)} but "
            f"{CONTRACT.relative_to(ROOT)}'s {ROLE_SCHEMA}.role enum has {sorted(contract)} — the entity and the "
            f"contract must name exactly the same roles (ADR 0007)")

    if errors:
        print("SCOPE MODEL VOCABULARY VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        return 1

    print(
        f"scope model vocabulary OK: {len(paths)} docs carry no scope-keypair, `ADMIN`-role or `ACTIVE`-"
        f"membership phantom; MembershipEntity roles == the contract's {ROLE_SCHEMA}.role enum "
        f"({sorted(contract)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
