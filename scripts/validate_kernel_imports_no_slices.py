#!/usr/bin/env python3
"""Guard what the module boundary cannot: inside core, the kernel names no feature slice; and no platform file
names a node kind an application owns (developer-styleguide § 1, ADR 0007's shape, ADR 0079).

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
  (`me.raddatz.veyq.core.auth.AccessTokenResolver`, which `SessionService` implements), and one that needs a slice's
  table gets the entity moved into the kernel (`me.raddatz.veyq.core.tenant.TenantEntity`,
  `me.raddatz.veyq.core.scope.ScopeEntity`, `me.raddatz.veyq.core.membership.MembershipEntity`) with the slice's
  store still its only writer. Every `KERNEL` entry must be declared by some main file, so the list cannot rot into
  names that guard nothing, and a run in which no file is kernel is red.
- **Node-kind rule (ADR 0079).** Core knows the node kind `FOLDER` only; the kinds an application owns
  (`APPLICATION_KINDS`: Memories' `MEMORY` and `CHAPTER`, Files' `FILE` and `FILES_DROPBOX`) are named only by that application's code, which answers
  for them through core's `NodeKindRules`, `ShareRoots`, `GuestNodeRules`, `WriteOnlyGuestKinds`, `ShareGuestWrites`,
  `UploadCompletion` and `UploadAdmission` (`GuestNodeRules` and `WriteOnlyGuestKinds` by declaring nothing: a kind no
  one declares allows a guest no tree operation). A
  **platform** file is every main file outside an application module (a project `:apps:<name>`, read by
  `backend_tree.project_of`), an **application** file one inside. So no platform main file names one of them as a
  whole word **in code**
  — a constant, an enum entry (`NodeKind.MEMORY`) or a string literal (`kind == "MEMORY"` says what the constant
  said, so literals are read here, unlike the two rules above; comments are not). The contract's `NodeKind` is an
  open, bounded token (cr-platform-contract-split slice 4): the generated models carry it as a `String` and no
  generated type lists a kind, so a literal or an application's constant is the only way core could name one — and
  both are what this rule reads. Each kind must still be named in code by some application main file, so the list
  cannot rot into names that guard nothing. **The blob roles an application owns** (`APPLICATION_BLOB_ROLES`:
  Memories' `ORIGINAL`, `THUMBNAIL`, `PREVIEW`, Files' `FILE_CONTENT`; `COVER` is core's own since inbox #411, `NodeCover`) are read by the same rule since the contract's `BlobRole`
  opened to the same kind of token (cr-files-application P3-S2): core stores a role and compares it
  (`UploadSessionEntity.boundTo`), and which roles exist is the application's (`MemoryBlobRoles`, `FilesBlobRoles`),
  declared to core's member start through `MemberBlobRoles` (inbox #366).
  The lists are by hand (a token is a value, not a path): a new application adds its kinds and roles here — Files'
  sat out of them for its whole build until its harvest, and nothing turned red, because a missing entry guards nothing
  silently. A
  node's cover is core's attachment slot `COVER` (inbox #341, core's own feature since #411): core names the token because
  the cover is a platform feature, and no kind may be named for it (`NodeKindRules.takesCover` is each kind's answer).

**What it no longer asserts: core importing an application.** Until the Gradle module split it also held every
non-Memories main file away from the `me.raddatz.veyq.memories` packages. That is the build's now: `:core` has no project
edge to an application (`validate_backend_module_graph.py` rule (b); a `:core` → application edge is also a cycle
Gradle refuses), so such an import does not compile — measured: `Unresolved reference 'memories'` from
`:core:compileKotlin`. A text rule beside the compiler would only be a second, weaker copy of it.

**Why a declared list (owner's decision, 2026-10-04, `cr-platform-gradle-modules` open question 3).** The layering
was once a directory: the kernel lived under `shared/`, the slices under `features/`, and the guard barred
`shared/` from naming `features`. The package rename of that CR folds both trees into `me.raddatz.veyq.core.<feature>`,
which leaves the layering no name to hang on; the 15 former `shared/` packages became this list instead of a
`core.shared` sub-tree whose name says nothing the module does not.

**The defect that bought it.** No incident — built with `cr-shared-imports-no-features` (inbox #173), which
promoted its one-shot proof to this guard. The rule had been prose in the developer styleguide ("`shared/` must
not depend on a feature") with nothing reading it, and the 2026-09-23 review found four imports breaking it:
`SessionTokenIdentityProvider` on `SessionService`, and three tenant-kernel classes on the tenant entity, which then
still lived in the admin feature. A cycle through the kernel is the kind that grows: every slice may depend on the
kernel, so one back-edge makes every slice reachable from every other.

**What bought the node-kind rule.** No incident: neither an import rule nor the compiler can see a kind, because a
kind is a string, not a package — after phase 1, `NodesHandler.parentAllows` still decided where a `MEMORY` and a
`CHAPTER` may live, and the import rule of that time was green on it. The rule was red on exactly the four core lines (the two constants on
`NodeEntity`'s companion and the two `parentAllows` arms that name them) on the tree before the step moved them
behind `NodeKindRules`.

**Its limit.** Kernel membership is read from the file's `package` line, not its directory: Kotlin allows a
package that differs from the path, and a file that declares a kernel package is treated as one wherever it sits
(`validate_backend_module_graph.py` rule (d) holds a package to one module). For the node-kind rule, platform or
application is the module the file sits in. A slice
importing another slice is not this guard's subject — slices may use each other. Two things are not checked, on
purpose:
- **The test tree.** Kernel *tests* legitimately import slices — `OperatorBlindProbeTest`, the rate-limit tests,
  `SessionTokenIdentityProviderTest` (it issues a real session to resolve), `TenantIngressProjectorTest` — and
  integration tests may; a test is a consumer of both sides, not a dependency of either.
- **Comments and string literals.** Each file is read through the Kotlin lexer `validate_coverage` uses too
  (`kotlin_lexer.mask`), which blanks comments and literal contents at unchanged offsets, so a KDoc link to a slice
  class by its qualified name is documentation, not a dependency, and is not read. The other side of the same cut:
  a qualified name inside a **string** (`Class.forName("…")`) is a real runtime back-edge and is masked away with
  the rest. None exists; reflection by name is not a shape this codebase uses. (The node-kind rule keeps
  literals — see above.)
- **A kind spelled otherwise.** The node-kind rule matches the kind's own spelling as a whole word; a core file
  that assembles it (`"MEM" + "ORY"`) or compares case-insensitively passes. That is evasion, not a slip, and no
  text rule decides it.
The web counterpart of the backend's core → application boundary is `validate_web_core_imports_no_applications.py`
(no allowlist: any violation is red) — the web has no module boundary to hand that rule to. The *kernel rule* has none on the web side: `shared/ui/SecretKeyField.vue` imports the Emergency
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
    "me.raddatz.veyq.core.adminchannel",
    "me.raddatz.veyq.core.appshell",
    "me.raddatz.veyq.core.auth",
    "me.raddatz.veyq.core.blobstore",
    "me.raddatz.veyq.core.config",
    "me.raddatz.veyq.core.crypto",
    "me.raddatz.veyq.core.http",
    "me.raddatz.veyq.core.identity",
    "me.raddatz.veyq.core.instance",
    "me.raddatz.veyq.core.membership",
    "me.raddatz.veyq.core.persistence",
    "me.raddatz.veyq.core.ratelimit",
    "me.raddatz.veyq.core.scope",
    "me.raddatz.veyq.core.tenant",
    "me.raddatz.veyq.core.validation",
]
# The node kinds the applications own (ADR 0079 Decision 3). Core names `FOLDER` only.
APPLICATION_KINDS = [
    "MEMORY",
    "CHAPTER",
    "FILE",
    "FILES_DROPBOX",
]
# The blob roles the applications own (cr-files-application P3-S2): the contract's `BlobRole` is an open token like the kind,
# so core stores and compares a role and names none. Read by the same rule as the kinds.
APPLICATION_BLOB_ROLES = [
    "ORIGINAL",
    "THUMBNAIL",
    "PREVIEW",
    "FILE_CONTENT",
]
APPLICATION_TOKENS = APPLICATION_KINDS + APPLICATION_BLOB_ROLES
KIND_REF = re.compile(r"(?<!\w)(" + "|".join(re.escape(k) for k in APPLICATION_TOKENS) + r")(?!\w)")
# A qualified name in code, not preceded by another name part. Its package is resolved against the declared ones.
QUALIFIED = re.compile(r"(?<![\w.])me\.raddatz\.veyq(?:\.\w+)+")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)", re.M)


def in_application(path: pathlib.Path) -> bool:
    """True for a file inside an application module (a project `:apps:<name>`)."""
    return (backend_tree.project_of(path) or "").startswith(":apps:")


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
    code_of: dict[pathlib.Path, list[str]] = {}  # comments blanked, literals kept (the node-kind rule)
    for path in files:
        source = path.read_text(encoding="utf-8")
        masked = mask(source)
        m = PACKAGE.search(masked)
        read.append((path, m.group(1) if m else None, source.splitlines(), masked.splitlines()))
        code_of[path] = mask(source, literals=False).splitlines()
    declared = {pkg for _, pkg, _, _ in read if pkg}

    errors: list[str] = []
    stale = [k for k in KERNEL if not any(under(pkg, [k]) for pkg in declared)]
    if stale:
        errors.append("KERNEL names packages no main file declares — the list has rotted or the package moved: "
                      + ", ".join(stale))
    kernel = [r for r in read if under(r[1], KERNEL)]
    platform = [r for r in read if not in_application(r[0])]
    application_files = len(read) - len(platform)
    if not kernel:
        errors.append("no main Kotlin file declares a KERNEL package; the kernel rule would pass every file by "
                      "default. If the kernel moved, move KERNEL with it.")
    if not application_files:
        errors.append("no main Kotlin file sits in an application module (:apps:<name>); the node-kind rule would "
                      "read every file as platform and find the kinds named by nobody. If the modules moved, "
                      "re-point backend_tree.")
    application_named = {k.group(1) for path, _, _, _ in read if in_application(path)
                         for line in code_of[path] for k in KIND_REF.finditer(line)}
    unnamed = [k for k in APPLICATION_TOKENS if k not in application_named]
    if application_files and unnamed:
        errors.append("APPLICATION_KINDS/APPLICATION_BLOB_ROLES name tokens no application main file names in code — "
                      "the list has rotted or the token was renamed: " + ", ".join(unnamed))
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
        for lineno, line in enumerate(code_of[path], start=1):
            k = KIND_REF.search(line)
            if k:
                hits.setdefault((path, lineno), f"  - {path.relative_to(ROOT)}:{lineno}: platform names the "
                                                f"application node kind or blob role {k.group(1)}: {source[lineno - 1].strip()}")

    if hits:
        print("KERNEL-IMPORTS FAILED — a kernel package names a slice (by import or fully-qualified reference), "
              "or a platform file names a node kind or blob role an application owns (developer-styleguide § 1, ADR 0079):")
        print("\n".join(hits[k] for k in sorted(hits)))
        print("Share the rule through a small interface in the kernel that the slice implements, or move the entity "
              "into the kernel with the slice's store still its only writer. A rule about a Memories node kind or blob role is "
              "Memories' — answer it through a core seam (NodeKindRules, ShareRoots, GuestNodeRules, WriteOnlyGuestKinds, ShareGuestWrites, UploadCompletion, "
              "UploadAdmission, MemberBlobRoles).")
        return 1

    print(f"kernel imports OK: {len(kernel)} kernel file(s) in {len(KERNEL)} declared package(s), none names one of "
          f"the {len(declared - {p for p in declared if under(p, KERNEL)})} slice package(s) in code; "
          f"{len(platform)} platform file(s) (every main file outside the {application_files} in application modules), "
          f"none names an application node kind ({', '.join(APPLICATION_KINDS)}) or blob role ({', '.join(APPLICATION_BLOB_ROLES)})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
