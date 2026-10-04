#!/usr/bin/env python3
"""Guard that every routability-changing tenant operation re-projects the reverse proxy's routing (ADR 0063).

**What it asserts.** In `TenantHandler.kt`, every function whose body calls a `TenantStore` method that changes
*whether a tenant's domain routes* — creating a tenant, deleting one, moving its domain, suspending or
reactivating it — also calls the ingress projector (`reproject()`, or `ingress.regenerate()` directly) — the property being
protected is `uc.admin.tenant-reachability`'s, and this is its structural half. And the
set of routability-changing store methods is **derived from `TenantStore.kt` itself**, not listed here: a method
qualifies when its body creates a `TenantEntity`, deletes one, or `UPDATE`s the `domain` or `status` column. So a
sixth such store method added tomorrow is in scope the moment it lands, with nothing to register — a list
restated in this file is precisely the thing that would drift out of step with the code that runs.

**The defect that bought it.** No incident — nothing has shipped multi-tenant. It was built with
cr-database-driven-tenant-ingress, which introduced the projector and its five call sites, and it exists because
a missed call site is **silent**: the tenant row is correct, the routing is stale, no type and no compiler
notices, and nothing surfaces until somebody opens a domain that does not resolve. That CR's own bite test
(comment out one of the five, watch `TenantIngressProjectorTest` go red) proved the list complete *once*; this
is what keeps it complete.

**Its limit.** Three, and the first is the sharp one:

  (a) **It only reads `TenantHandler.kt`.** Routability is defined by the projector's predicate
      `status != SUSPENDED`, which is exactly what makes acceptance (PENDING → ACTIVE, `TenantStore.activate`
      from `TenantAcceptHandler`) *not* a trigger — the tenant already routed. Narrow that predicate to
      `status == ACTIVE` and `TenantAcceptHandler` becomes a sixth site that this guard cannot see, because it
      does not scan that file. The predicate and this corpus are coupled, and only prose says so (here, and in
      `TenantIngressProjector`'s own KDoc).
  (b) It proves the **call exists**, never that it runs at the right moment. `reproject()` must be reached
      *after* the caller's transaction commits — the projector reads in a new transaction, so a call from inside
      one regenerates from the pre-change table. That is behaviour, and `TenantIngressProjectorTest` is what
      pins it.
  (c) It proves nothing about the *sink*. Whether the written file is what Traefik actually loads is a property
      of `infra/docker-compose.prod.yml` and of Traefik, neither of which any deterministic check here executes.

Its own source is not in what it scans (the corpus is two Kotlin files under `backend/`), so the usual trap — a
guard matching the literal it forbids inside its own documentation — does not apply.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
STORE = backend_tree.source_file("com.veyq.core.admin.tenant.TenantStore")
HANDLER = backend_tree.source_file("com.veyq.core.admin.tenant.TenantHandler")
PROJECTOR = backend_tree.source_file("com.veyq.core.tenant.TenantIngressProjector")

# A function declaration at class-member indentation, in either file.
FUN = re.compile(r"^\s{4}(?:(?:private|internal|public|override|@Transactional\s+)\s*)*fun\s+(\w+)\s*[(<]")

# The three shapes that change whether a tenant routes, read off a TenantStore function body.
CREATES = re.compile(r"TenantEntity\(\)\s*\.apply")
DELETES = re.compile(r"TenantEntity\.(?:delete|deleteById)\b")
# `TenantEntity.update("domain = …")` / `("status = …")` — the column, at the head of the SET clause or after a
# comma, never inside the WHERE (`and status = ?3` is a guard, not a write).
WRITES_ROUTING_COLUMN = re.compile(r"TenantEntity\.update\(\s*\n?\s*\"\s*(?:domain|status)\s*=")

# What discharges the obligation. `reproject()` is the handler's own one-liner onto the projector; the direct
# form is accepted so a future caller that skips the helper is not reported as a violation for style alone.
REPROJECTS = re.compile(r"\breproject\(\)|\bingress\.regenerate\(\)")


def strip_comments(source: str) -> str:
    """Blank out `//` and `/* */` comments, keeping line numbers. KDoc discusses these very calls."""
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        two = source[i:i + 2]
        if two == "//":
            while i < n and source[i] != "\n":
                i += 1
        elif two == "/*":
            i += 2
            while i < n and source[i:i + 2] != "*/":
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            i += 2
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def functions(path: pathlib.Path) -> dict[str, tuple[int, str]]:
    """`name -> (first line, body)` for every class-member function, body running to the next declaration."""
    lines = strip_comments(path.read_text()).splitlines()
    starts = [(i, m.group(1)) for i, line in enumerate(lines) if (m := FUN.match(line))]
    found: dict[str, tuple[int, str]] = {}
    for idx, (start, name) in enumerate(starts):
        end = starts[idx + 1][0] if idx + 1 < len(starts) else len(lines)
        found[name] = (start + 1, "\n".join(lines[start:end]))
    return found


def main() -> int:
    for path in (STORE, HANDLER, PROJECTOR):
        if not path.is_file():
            print(f"missing {path.relative_to(ROOT)} — the tenant ingress projection moved or was deleted")
            return 1

    routing_writers = {
        name for name, (_, body) in functions(STORE).items()
        if CREATES.search(body) or DELETES.search(body) or WRITES_ROUTING_COLUMN.search(body)
    }
    if not routing_writers:
        # A zero here means the shapes above stopped matching (Panache idiom changed, the store was split), not
        # that nothing writes routing — and a guard whose corpus silently emptied reports green forever.
        print(f"no routability-changing methods found in {STORE.relative_to(ROOT)} — this guard's shapes are stale")
        return 1

    errors: list[str] = []
    obliged = 0
    for name, (line, body) in functions(HANDLER).items():
        called = sorted(m for m in routing_writers if re.search(rf"\bstore\.{m}\s*\(", body))
        if not called:
            continue
        obliged += 1
        if not REPROJECTS.search(body):
            errors.append(
                f"{HANDLER.relative_to(ROOT)}:{line}: `{name}` calls {', '.join('store.' + c for c in called)} "
                f"— routability changes here, so it must end in reproject() (ADR 0063: never an incremental patch)"
            )

    if not obliged:
        print(f"no {HANDLER.relative_to(ROOT).name} function calls a routability-changing store method — corpus stale")
        return 1

    if errors:
        print("tenant ingress triggers: a routability change that never reaches the projector\n")
        for e in errors:
            print(f"  {e}")
        print(
            "\nThe row would be right and the routing stale, with nothing to notice: a domain that does not "
            "resolve, or one that still does after the tenant was suspended."
        )
        return 1

    print(
        f"tenant ingress triggers: {obliged} routability-changing handler operation(s) reproject "
        f"({len(routing_writers)} store method(s) in scope)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
