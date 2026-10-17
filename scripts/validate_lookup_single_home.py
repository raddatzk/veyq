#!/usr/bin/env python3
"""Guard that four security-relevant lookups each have exactly one home in the backend.

**What it asserts.** Over every main Kotlin file of the backend (`backend_tree`), four kinds of lookup occur only in their home:

  A. **A request's Host header is read** only in `core/tenant/TenantHostResolver.kt`. Spellings: a string
     literal naming the forwarded-host header (any case), a `getHeader`/`getHeaderString`/`header` call whose
     literal argument is the plain host header (any case), the `HttpHeaders` host constant, and Vert.x's
     `.host()` / `.authority()` on a request.
  B. **A tenant is looked up by domain** only in `TenantHostResolver.kt`: a Panache `find`/`count`/`list`/`stream`
     whose query string starts with the `domain` column.
  C. **A user is looked up by email** only in `core/identity/AccountStore.kt`: `VeyqUser.find`/`count`/`list`/
     `stream` whose query string starts with the `email` column.
  D. **A client's forwarded address is read** only in `core/http/ClientAddress.kt` (ADR 0094 decisions 1 and 7): a
     string literal naming the forwarded-for header, or the client-port header an edge overwrites (each in any case),
     wherever it stands — a call argument, a constant, a map key.

Each home must itself still carry its rule's lookup, so a home that moved or was renamed reads red ("nothing
there to be the only one"), never as a vacuous green. Rule D's home must carry EACH of its two literals: it holds two
headers, and a home that kept one would leave the other's single home unchecked. A header read through the resolver
(`hosts.hostOf { headers.getHeaderString(it) }`) is not a hit: it passes the resolver's own header names, which
is the point of having one resolver.

**The defect that bought it.** Inbox #156, cr-request-tenant-and-tenant-bound-recovery. Host → tenant was resolved
in four places, and `RecoveryResource` hand-parsed the raw header to pick the tenant's SMTP relay while looking the
account up across the whole instance — so a tenant owner received recovery links for accounts in other tenants
(ADR 0054 §4). The same CR found six copies of email normalization and four user-by-email lookups; one lookup with
one normalization is what makes "the account under this address" mean the same thing everywhere. This guard's
first run on the untouched tree was red on exactly the CR's inventory: two hand parses, three domain lookups, four
email lookups outside their homes.

Rule D came with cr-client-address-resolver (ADR 0094). Quarkus's forwarding took the leftmost forwarded-for entry,
the one a client writes, and keyed the per-IP rate limiter on it; the guest-write record of ADR 0093 § 10 will keep
the address as evidence. A second hand parse there takes the leftmost entry again and records a forged address
that names a third party, and every test that sends a single-entry header stays green, so no human notices. Its
first run, before `ClientAddress.kt` existed, was red on the missing home. Its port header came with
cr-client-port-sources (ADR 0094 decision 7): the resolver reads it only when the operator says the edge overwrites
it, and a second, unconditional reader — the guest-write record is the next one to want the port — would honour a
port any client forged and name another subscriber behind the same CGNAT address; a test that sends no such header
notices nothing. Its first run, before the resolver held the header's name, was red on the missing home.

**Its limit.**

  (a) **An alias defeats it.** `const val H = "Host"` read as `getHeaderString(H)` is a header read no spelling
      above matches: the literal is not an argument and the call has no literal. Catching it needs data flow,
      which a buildless grep does not have. `validate_guard_gaps.py` pins this gap, so closing it turns that
      guard red instead of landing silently. Rule D matches its literal anywhere, so a constant holding the name
      is caught; what defeats it is a name that is never one literal — assembled from two (`"X-Forwarded-" +
      "For"`), or matched by a prefix over every header — which is the same data-flow gap, pinned the same way. The
      port header is read through the configured name (`veyq.http.client-port.header`), so a reader that takes the
      name from configuration rather than as a literal is invisible to it too.
  (b) **It reads text, not code.** A comment or KDoc that spells a forbidden call is red too: describe the call
      instead of writing it. (Masking comments was considered and dropped: `validate_coverage.py`'s Kotlin
      masker blanks string literals as well — the very thing rules A–C read — and that module runs its whole
      check on import, so it cannot be reused as a library.) The converse also holds: a query assembled at
      runtime (`find(column, value)`), HQL outside Panache's shorthand (`"from VeyqUser where email = ?1"`), or a
      native query is invisible.
  (c) **It proves where a lookup lives, not that its callers agree — nor that the home reads conditionally.** Rule
      D says the port header is read in one file, not that that file reads it only under `source=header`: that is
      behaviour, pinned by `ClientAddressDefaultSourceSliceTest.aClientSentPortHeaderIsNeverHonoured`. One home for the email lookup does not make
      every caller normalize before calling; `AccountStore` normalizes for itself, which is what closes that.
      And one home for Host resolution does not say which tenant a handler then acts on — whether recovery mails
      only a member of that tenant is behaviour, pinned by `RecoverySliceTest`.

Its own source is not in what it scans (the corpus is Kotlin under `backend/src/main`), so the usual trap — a guard
matching the literal it forbids in its own documentation — does not apply.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESOLVER = backend_tree.source_file("me.raddatz.veyq.core.tenant.TenantHostResolver")
ACCOUNTS = backend_tree.source_file("me.raddatz.veyq.core.identity.AccountStore")
CLIENT_ADDRESS = backend_tree.source_file("me.raddatz.veyq.core.http.ClientAddress")

# A double-quoted literal's content, at the position a pattern leaves off. Kotlin allows `"…"` only here; a raw
# `"""…"""` query is out of reach, see limit (b).
LITERAL = r'"(?P<lit>[^"\\\n]*)"'

RULES: list[tuple[str, str, pathlib.Path, list[re.Pattern[str]]]] = [
    ("A", "a request Host header read", RESOLVER, [
        re.compile(r'"x-forwarded-host"', re.I),
        re.compile(r'\b(?:getHeaderString|getHeader|header)\(\s*"host"\s*\)', re.I),
        re.compile(r'\bHttpHeaders\s*\.\s*HOST\b'),
        re.compile(r'\.\s*(?:host|authority)\(\s*\)'),
    ]),
    ("B", "a tenant looked up by domain", RESOLVER, [
        re.compile(r'\b(?:find|count|list|stream)\(\s*' + LITERAL),
    ]),
    ("C", "a user looked up by email", ACCOUNTS, [
        re.compile(r'\bVeyqUser\s*\.\s*(?:find|count|list|stream)\(\s*' + LITERAL),
    ]),
    ("D", "a client's forwarded address read", CLIENT_ADDRESS, [
        re.compile(r'"x-forwarded-for"', re.I),
        re.compile(r'"x-veyq-client-addr"', re.I),
    ]),
]

# Rules whose home must carry every one of its patterns, not just one: rule D's two literals are two headers.
EVERY_PATTERN_HOME = {"D"}

# Rules B and C match any query string and then ask which column it starts with; the column is the predicate.
COLUMN = {"B": "domain", "C": "email"}


def hits(rule: str, patterns: list[re.Pattern[str]], src: str) -> list[int]:
    """1-based line numbers where `src` carries a lookup of this rule."""
    lines = []
    for p in patterns:
        for m in p.finditer(src):
            column = COLUMN.get(rule)
            if column and not re.match(rf"\s*{column}\b", m.group("lit")):
                continue
            lines.append(src.count("\n", 0, m.start()) + 1)
    return sorted(set(lines))


def main() -> int:
    files = backend_tree.main_kotlin()
    if not files:
        print(f"FAIL lookup-single-home: no Kotlin under {backend_tree.BACKEND.relative_to(ROOT)}/**/src/main/kotlin "
              "— nothing was checked")
        return 1
    sources = {f: f.read_text(encoding="utf-8") for f in files}

    failures = []
    for rule, what, home, patterns in RULES:
        if home not in sources:
            failures.append(f"rule {rule}: home {home.relative_to(ROOT)} is missing — {what} has no home to be in")
            continue
        required = [[p] for p in patterns] if rule in EVERY_PATTERN_HOME else [patterns]
        for group in required:
            if not hits(rule, group, sources[home]):
                failures.append(f"rule {rule}: home {home.relative_to(ROOT)} no longer carries {what} "
                                f"({' / '.join(p.pattern for p in group)}) — it moved; re-point this guard rather than "
                                "letting it pass vacuously")
        for f, src in sources.items():
            if f == home:
                continue
            for line in hits(rule, patterns, src):
                failures.append(f"rule {rule}: {what} outside {home.name}: {f.relative_to(ROOT)}:{line}")

    if failures:
        print("FAIL lookup-single-home:")
        for msg in failures:
            print(f"  {msg}")
        return 1
    print(f"lookup-single-home OK: {len(files)} Kotlin file(s); Host read + tenant-by-domain only in "
          f"{RESOLVER.name}, user-by-email only in {ACCOUNTS.name}, forwarded client address and client-port "
          f"header only in {CLIENT_ADDRESS.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
