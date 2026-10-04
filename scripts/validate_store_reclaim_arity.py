#!/usr/bin/env python3
"""Guard that exactly one place in the backend deletes store objects, and that it is the reclaim sweep (ADR 0062 Decision 3).

**What it asserts.** Across `backend/src/main/kotlin`, with comments and string literals removed, there is
**exactly one** call of `delete(...)` on a `BlobStore`, and it sits in
`core/blobstore/StoreReclaimSweep.kt`. Every other path that drops a row carrying a `storage_key` writes the
key to `veyq_store_reclaim` inside its own transaction and lets that sweep do the deleting.

The invariant is not tidiness. Nine call sites meant nine places that could fail, none of which could report a
failure — the port returned `Unit` and the S3 implementation swallowed every per-key error — while the row that
named the key was already gone. Collapsing them to one is what makes a refused delete retryable at all, and
this is the decision every future reclaim path will be tempted to bypass, because calling the store directly is
one line and putting a row on a list is three. A bypass reintroduces the original defect in full and is
invisible from every test: the hermetic tier runs on a store whose `delete` is an empty no-op.

Two keys, and the second exists because the first infers a type:

  - *typed receiver* — names declared `: BlobStore` in a file (constructor parameters, properties), then
    `<name>.delete(` anywhere in that file. This is the load-bearing key: it survives the field being renamed,
    which the obvious `grep` for one spelling does not, and which reads the type through an optional package
    qualifier — the shape that made this key's first version report green over a forged bypass.
  - *conventional receiver* — any `blobStore.delete(` regardless of a visible declaration. It covers the one
    thing the typed key cannot see: a receiver whose `BlobStore` type is **inferred** (`val s =
    producer.blobStore()`), which carries no `: BlobStore` to key on. Its own blind spot is the opposite one —
    a differently-named receiver — so the two disagree exactly where one of them is wrong, and the guard reports
    the union.

**The defect that bought it.** No incident — nothing has shipped. It was built with
cr-store-object-reclaim-list, which collapsed those nine call sites, and it exists because that CR's own
correctness is a structural claim that a `grep` answers once and nothing keeps true. Its immediate neighbour
`validate_blob_reference_arity.py` names this hole from the other side in its own limit: *"a new reclaim
predicate elsewhere gets no protection from this guard at all"*.

**Its limit.** Three, and the first is the one that matters:

  (a) It sees **calls that name a receiver**. A `BlobStore` reached without one — through an injected CDI
      `Instance<BlobStore>`, a reflective lookup, or a delete issued by a Kotlin extension function defined
      elsewhere — is invisible to both keys. There is no such site today and none of those shapes appears
      anywhere in this backend, which is what makes the receiver assumption safe rather than lucky.
  (b) Its corpus is `backend/src/main/kotlin` only. Test scope is deliberately outside it: `RecordingBlobStore`
      delegates a `delete` and must, and a test is entitled to drive the port directly. So this proves nothing
      about what tests do — and nothing about `web`, which cannot reach a store credential at all.
  (c) It proves the call site is **alone**, never that it is **correct**. That the sweep keeps a row whose keys
      the store refused is behaviour, and `StoreReclaimSweepSliceTest` is what pins it.

Its own source is **not** in what it scans (the corpus is Kotlin under `backend/`), so the usual trap — a guard
matching the literal it forbids inside its own documentation — does not apply here and nothing is excluded to
avoid it. Do not narrow the corpus to make that true; it already is.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The one call site this decision permits, as a repo-relative path.
ALLOWED_SITE = backend_tree.source_file("com.veyq.core.blobstore.StoreReclaimSweep").relative_to(ROOT).as_posix()

# `foo: BlobStore` — a constructor parameter or property whose declared type is the port. The optional
# `[\w.]+\.` qualifier is not decoration: without it a fully-qualified `foo: com.veyq.core.blobstore
# .BlobStore` is legal Kotlin that this key cannot see, and a bite forged in exactly that shape ran GREEN over a
# tree carrying a live bypass. It does now also match `VeyqConfig.BlobStore`, the config sub-interface of the
# same simple name — which can never become a false positive, because a hit needs a `.delete(` call too and that
# interface has no such member.
TYPED_RECEIVER = re.compile(r"\b(\w+)\s*:\s*(?:[\w.]+\.)?BlobStore\b")

# The conventional field name, used everywhere the port is injected.
CONVENTIONAL_RECEIVER = "blobStore"


def strip_noise(source: str) -> str:
    """Blank out comments and string literals, keeping line numbers intact.

    Prose is not code: three KDoc paragraphs in this backend discuss `blobStore.delete` in order to explain why
    it is no longer called, and a guard that counts them reports the documentation of the rule as a violation of
    it. Strings go too, so a log message quoting the call cannot trip the guard either. Newlines are preserved
    so every hit still reports the line the reader has to open.
    """
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        ch = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
        elif ch == "/" and nxt == "*":
            i += 2
            while i < n and not (source[i] == "*" and i + 1 < n and source[i + 1] == "/"):
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            i += 2
        elif ch == '"':
            # Triple-quoted strings first: their content may hold a bare `"`.
            if source.startswith('"""', i):
                i += 3
                while i < n and not source.startswith('"""', i):
                    out.append("\n" if source[i] == "\n" else " ")
                    i += 1
                i += 3
            else:
                i += 1
                while i < n and source[i] != '"':
                    if source[i] == "\\":
                        i += 1
                    i += 1
                i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def call_sites() -> tuple[list[str], list[str]]:
    """(typed-receiver hits, conventional-receiver hits) as `path:line` strings."""
    typed: list[str] = []
    conventional: list[str] = []
    for path in backend_tree.main_kotlin():
        code = strip_noise(path.read_text(encoding="utf-8"))
        rel = path.relative_to(ROOT).as_posix()
        names = set(TYPED_RECEIVER.findall(code))
        patterns = {name: re.compile(rf"\b{re.escape(name)}\s*\.\s*delete\s*\(") for name in names}
        conventional_pattern = re.compile(rf"\b{re.escape(CONVENTIONAL_RECEIVER)}\s*\.\s*delete\s*\(")
        for lineno, line in enumerate(code.splitlines(), start=1):
            if any(p.search(line) for p in patterns.values()):
                typed.append(f"{rel}:{lineno}")
            if conventional_pattern.search(line):
                conventional.append(f"{rel}:{lineno}")
    return typed, conventional


def main() -> int:
    if not backend_tree.main_roots():
        print(
            "STORE RECLAIM ARITY VALIDATION FAILED:\n"
            f"  - missing tree: no {backend_tree.BACKEND}/**/src/main/kotlin. This guard reads backend sources directly; a moved tree must be "
            f"re-pointed, not silently skipped."
        )
        return 1

    typed, conventional = call_sites()
    sites = sorted(set(typed) | set(conventional))
    errors: list[str] = []

    for site in sites:
        if not site.startswith(ALLOWED_SITE + ":"):
            errors.append(
                f"{site}: deletes store objects directly. Only StoreReclaimSweep may (ADR 0062 Decision 3) — "
                f"record the key prefix on `veyq_store_reclaim` in the same transaction as the row delete "
                f"instead, via `StoreReclaimStore.record`. A direct call cannot report a refusal to anything "
                f"that could retry it, and by the time it fails the row naming the key is already gone."
            )
    if not sites:
        errors.append(
            f"no call of `delete(...)` on a BlobStore anywhere in the backend's main Kotlin. The sweep is "
            f"the only deleter, so zero call sites means nothing reclaims store objects at all — every purge "
            f"now writes reclaim rows that are never drained. If the sweep moved, re-point ALLOWED_SITE."
        )
    elif len(sites) > 1 and all(s.startswith(ALLOWED_SITE + ":") for s in sites):
        errors.append(
            f"the sweep hands keys to the store from {len(sites)} places ({', '.join(sites)}). One call site is "
            f"what makes 'a row settles only if none of its keys were refused' expressible; a second one "
            f"partitions that decision across two returns."
        )

    disagreement = sorted(set(typed) ^ set(conventional))
    if disagreement:
        errors.append(
            f"the two keys disagree at {', '.join(disagreement)}. Typed-receiver found {sorted(set(typed))}, "
            f"conventional-receiver found {sorted(set(conventional))}. A site only one key sees is a real site: "
            f"either a BlobStore held under an inferred type, or one held under a name other than "
            f"`{CONVENTIONAL_RECEIVER}`. Read it before changing either key."
        )

    if errors:
        print("STORE RECLAIM ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(f"OK: exactly 1 BlobStore delete call site in backend main, at {sites[0]}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
