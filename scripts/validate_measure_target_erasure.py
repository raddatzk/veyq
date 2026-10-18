#!/usr/bin/env python3
"""Guard that every eraser of a moderation measure's target ends the measures on it (GDPR Art. 5(1)(e); ADR 0093 §§ 6,
11; `MeasureTargets`).

**What it asserts.** Every backend main Kotlin function that erases a row a measure can stand on calls the ending —
`.erased(` (`MeasureTargets.erased`) or `targetErased(` (`SignInBlocks.targetErased`, the account's and the tenant's
purge) — itself, or delegates that erasure to a function of the same file that erases the same entity and ends them (a
share's `reissue` → `tombstone`; calling some other ending function, as `tombstone` calls `dropDeadIdentities`, does not
count for the link). The target set is **derived**, not listed:

  - the entities `MeasureTargets.lockStanding` locks (`XEntity.find(`), one per kind of measure — a new kind's target is
    in the set the moment the exhaustive `when` names it;
  - the entities every `ReportableItems` implementation locks in its `override fun lockExisting(` — an application's
    items (Memories: `MediaEntity`), which core cannot name;
  - and, where `lockStanding` reads a flag (`?.deleted == false`, a link's tombstone), the write of that flag on the
    entity — a property assignment `x.deleted = true`, or `XEntity.update("deleted = true …")` — counts as its erasure.

An erasure is `XEntity.delete(` / `XEntity.deleteById(` (bulk or by id) or the flag write, outside comments. A function
that erases a target and ends nothing is red, unless `EXEMPT` names it with the reason no measure can stand on what it
erases. Zero targets, zero `ReportableItems` implementations while `lockStanding` asks one, or zero erasers are red on
their own: each would make the guard vacuously green.

**The defect that bought it.** cr-account-erasure (2026-10-18) found that a scope purge left a content measure standing on
what it erased (ADR 0101 § Open, inbox #478): a measure in force holds its case's records (`ModerationRecordsPurge`),
so a block on an erased link, node, medium or guest uploader held them forever. The account's and the tenant's purges had
been given the ending one by one; the link's delete, the trash purge, the media purge, the identity drop and the scope
erasers had not, and nothing said they owed it. This guard was built with that fix, so the next eraser of a target owes
the call from its first line.

**Its limit.** It reads **text, not a running erasure**. (a) It proves the ending is *called* in the erasing function,
never that it is called with the right kinds and ids, after the delete, or in the same transaction — the per-path slice
tests (`MeasureOnErasedTargetSliceTest`, `ModerationMemoriesSliceTest`) and the race test
(`MeasureTargetErasureRacesSliceTest`) hold that half. (b) An erasure it cannot see is not checked: a native SQL or HQL
`delete from …` string, a cascade (`ON DELETE CASCADE`) onto a target table, a delete through a generic helper that takes
the entity as a parameter, or a flag other than the one `lockStanding` reads. (c) Function bodies are cut by indentation —
from a `fun` declaration to the first later line indented no deeper than it — so a local function inside an erasing one
is read as part of it, and a call into another file that ends the measures is not followed (red, conservatively; add the
call where the erasure is). (d) The decision's half — a measure taken on a target already gone ends at once — is held by
those tests only.
"""
from __future__ import annotations

import pathlib
import re
import sys

import backend_tree  # where the backend's sources live — resolved by glob, never spelled here

ROOT = pathlib.Path(__file__).resolve().parent.parent
MEASURE_TARGETS = "me.raddatz.veyq.core.moderation.block.MeasureTargets"
STANDING_FN = "fun lockStanding("
ITEM_FN = "override fun lockExisting("
ITEMS_CLASS = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?ReportableItems\b", re.MULTILINE)
LOCKED_ENTITY = re.compile(r"\b([A-Z]\w*)\.find\(")
FLAG = re.compile(r"\b([A-Z]\w*)\.find\([^\n]*(?:\n[^\n]*?)??\?\.(\w+) == false")
ENDING = re.compile(r"\.erased\(|\btargetErased\(")
FUN_DECL = re.compile(r"^(\s*)(?:[\w@]+(?:\([^)]*\))?\s+)*fun\s+(?:<[^>]+>\s+)?(?:[\w.]+\.)?(\w+)\s*\(")

# Functions that erase a target row but on which no measure can stand, each with the reason. Keyed (file stem, function).
EXEMPT = {
    ("TenantStore", "deleteTenant"): (
        "revokes a PENDING tenant only (`TenantHandler.revokeTenant`): a tenant measure is a suspension's or an active "
        "tenant's deletion record (`TENANT_OFFLINE`, `TENANT_DELETED`), and a pending tenant can be neither suspended "
        "(`TenantStore.suspend` moves ACTIVE only) nor deleted over content"
    ),
}


def _fn_body(text: str, start: str) -> str | None:
    """From [start] to the next member-level declaration — enough to read one short body."""
    if start not in text:
        return None
    rest = text.split(start, 1)[1]
    end = re.search(r"\n    (?:@|fun |override |private |internal |val |var |companion )", rest)
    return rest[: end.start()] if end else rest


def _code_only(text: str) -> str:
    """The text with comment lines blanked and trailing `//` comments cut (line numbers kept)."""
    out = []
    in_block = False
    for line in text.splitlines():
        s = line.strip()
        if in_block:
            out.append("")
            if "*/" in s:
                in_block = False
            continue
        if s.startswith("/*"):
            out.append("")
            in_block = "*/" not in s
            continue
        if s.startswith("*") or s.startswith("//"):
            out.append("")
            continue
        out.append(re.sub(r"\s//\s.*$", "", line))
    return "\n".join(out)


def functions(text: str) -> list[tuple[str, str]]:
    """(name, body) of every function, cut by indentation (see the docstring's limit c): from its declaration to the
    first later non-blank line indented no deeper than it — its closing `}` included, the next member excluded."""
    lines = text.splitlines()
    out = []
    for i, line in enumerate(lines):
        m = FUN_DECL.match(line)
        if not m:
            continue
        indent = len(m.group(1))
        end = len(lines)
        for j in range(i + 1, len(lines)):
            s = lines[j].strip()
            if s and len(lines[j]) - len(lines[j].lstrip()) <= indent:
                if s.startswith(")"):
                    continue  # a multi-line signature's close: `): Type {`
                end = j + 1 if s.startswith("}") else j
                break
        out.append((m.group(2), "\n".join(lines[i:end])))
    return out


def patterns_by_entity(named: list[str], flags: dict[str, str]) -> list[tuple[str, re.Pattern[str]]]:
    """(entity, pattern) of every erasure of [named]: a delete, and the write of the flag `lockStanding` reads."""
    out = [(e, re.compile(rf"\b{e}\.(?:delete|deleteById)\(")) for e in named]
    for e in named:
        if e in flags:
            f = flags[e]
            out.append((e, re.compile(rf"\b\w+\.{f} = true\b")))
            out.append((e, re.compile(rf"\b{e}\.update\(\"[^\"]*\b{f} = true\b")))
    return out


def targets(errors: list[str]) -> tuple[set[str], dict[str, str]]:
    """The target entities, and the flag whose write erases an entity (`ShareEntity` → `deleted`)."""
    path = backend_tree.source_file(MEASURE_TARGETS)
    if not path.is_file():
        errors.append(f"missing {MEASURE_TARGETS} — this guard derives its targets from `lockStanding`; re-point it.")
        return set(), {}
    body = _fn_body(path.read_text(encoding="utf-8"), STANDING_FN)
    if body is None:
        errors.append(f"`{STANDING_FN}` is gone from {path.name}: the guard derives every measure's target from it.")
        return set(), {}
    found = set(LOCKED_ENTITY.findall(body))
    flags = {entity: flag for entity, flag in FLAG.findall(body)}
    if "lockExisting(" in body:
        impls = []
        for p in backend_tree.main_kotlin():
            text = p.read_text(encoding="utf-8")
            if ITEMS_CLASS.search(text):
                impls.append(p.stem)
                item_body = _fn_body(text, ITEM_FN)
                locked = set(LOCKED_ENTITY.findall(item_body or ""))
                if not locked:
                    errors.append(
                        f"{p.stem} implements ReportableItems but its `{ITEM_FN}` locks no `XEntity.find(`: the guard "
                        f"cannot tell which rows its items are, so their erasers go unchecked."
                    )
                found |= locked
        if not impls:
            errors.append(
                "`lockStanding` asks `ReportableItems.lockExisting`, but no class implementing `ReportableItems` was "
                "found: an application's item erasers would go unchecked."
            )
    if not found:
        errors.append("`lockStanding` names no `XEntity.find(`: an empty target set is vacuously green.")
    return found, flags


def main() -> int:
    errors: list[str] = []
    entities, flags = targets(errors)
    erasers: list[str] = []
    for path in backend_tree.main_kotlin():
        raw = path.read_text(encoding="utf-8")
        named = [e for e in entities if re.search(rf"\b{e}\b", raw)]
        if not named:
            continue
        text = _code_only(raw)
        pairs = patterns_by_entity(named, flags)
        patterns = [p for _, p in pairs]
        fns = functions(text)
        # Per function: the target entities it erases, and whether it calls the ending itself.
        erases = {name: {e for e, p in pairs if p.search(body)} for name, body in fns}
        direct = {name for name, body in fns if ENDING.search(body)}
        bodies = dict(fns)

        def ends(name: str, entity: str, seen: frozenset[str] = frozenset()) -> bool:
            """[name] ends the measures on what it erases of [entity]: itself, or by delegating that erasure to a function
            of this file that erases the same entity and ends them (a share's `reissue` → `tombstone`)."""
            if name in direct:
                return True
            return any(
                g not in seen and entity in erases.get(g, set()) and re.search(rf"\b{re.escape(g)}\(", bodies[name])
                and ends(g, entity, seen | {name})
                for g in erases if g != name
            )

        for name, body in fns:
            hits = sorted({m.group(0) for p in patterns for m in p.finditer(body)})
            if not hits:
                continue
            key = (path.stem, name)
            if key in EXEMPT:
                continue
            erasers.append(f"{path.stem}.{name}")
            if not all(ends(name, e) for e in erases[name]):
                errors.append(
                    f"{path.relative_to(ROOT)} `{name}` erases a measure's target ({', '.join(hits)}) and ends no measure "
                    f"on it. A block, a bar or a sign-in block on that row then stands on nothing forever and holds its "
                    f"case's records with it (GDPR Art. 5(1)(e)). Call `MeasureTargets.erased(kinds, ids)` after the "
                    f"delete, in the same transaction — or, if no measure can stand on what it erases, add it to EXEMPT "
                    f"with the reason."
                )
    for stem, fn in sorted(EXEMPT):
        if f"{stem}.{fn}" in erasers:
            continue
        hit = any(
            p.stem == stem and any(n == fn for n, _ in functions(_code_only(p.read_text(encoding="utf-8"))))
            for p in backend_tree.main_kotlin(f"{stem}.kt")
        )
        if not hit:
            errors.append(f"EXEMPT names `{stem}.{fn}`, which no longer exists — drop the entry.")
    if entities and not erasers:
        errors.append("no function erasing a measure's target was found: the erasure shapes drifted, the guard reads nothing.")

    if errors:
        print("MEASURE TARGET ERASURE VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(
        f"OK: {len(erasers)} erasers of a measure's target ({', '.join(sorted(erasers))}) each end the measures on it; "
        f"targets {', '.join(sorted(entities))}."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
