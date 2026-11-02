#!/usr/bin/env python3
"""Every seal purpose is classified for a scope key's replacement, every value key's wrap is rewritten by a rotation's
commit — and **a rotation writes only wraps**: no rewriter assigns a value (ADR 0115 decisions 1-2, owner 2026-11-02).

**What it asserts.** Its subject is derived from the crypto package on every run, never listed: every seal purpose
`src/packages/crypto/src/seal.ts` declares — the keys of `SEAL_PURPOSES`, `FIELD_PURPOSES` and `SEAL_TO_PURPOSES` — and
its `VALUE_KEY_PURPOSES` list, read from the file with its comments stripped. Each purpose is in exactly one of:

  - `UNDER_VALUE_KEY`: a scope-level value that has an author, sealed under a value key `K_v` of its own, whose wrap
    alone is under the scope key. Each entry names the backend columns `Entity.column` the value is stored in
    (`values`) and the column its key's wrap is stored in (`wraps`). The set of these purposes must be exactly seal.ts'
    `VALUE_KEY_PURPOSES` — the crypto package's own statement of which values carry a value key.
  - `RESEALED_UNDER_SCOPE_KEY`: the exemption — sealed under the scope key itself and re-sealed by a rotation, with its
    columns and why it is exempt (a scope-root node's entry: any member may write it, ADR 0111 decision 11, and its slug
    token is a keyed MAC that must move with the key).
  - `WRAP_PURPOSE` (`value-key`): the wrap itself — its columns are every `wraps` entry above.
  - `NOT_UNDER_SCOPE_KEY`: sealed under another key, with that key and why a rotation leaves the value.

The rewriters are core's scope commit (`me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit`) and every class implementing
`ScopeRekeyParticipant` (an application's part of the commit). Each column must exist (an `@Column(name = "...")` in a
main Kotlin class of that name, read with the Kotlin property it maps). Then:

  - every `wraps` column's property is **assigned** (`.prop =`, or `prop = …` in a bulk update's SET part) in a
    rewriter that also names the entity (one property name can be two entities' — `wrappedValueKey` is a verification's
    and an item mark's);
  - **no rewriter assigns any column but a wrap** — every `@Column` property a rewriter assigns (`=`, `+=` and the like,
    or in a bulk update's SET part) is a `wraps` property or one of `REWRITER_METADATA`, with its reason. So a commit that writes a
    value's ciphertext — another member's name, a verification, an item mark — is red, and so is one that writes a
    value's epoch: the stage schemas carry no value fields, and this is the other half of "a rotation writes only
    wraps";
  - the exempt columns are assigned in `ROOT_ENTRY_REWRITER` (`ScopeRootEntries`), which the core commit calls and which
    names no entity holding a value — so what it assigns can only be a node's.

Red on a purpose in no table or in two, on a classified purpose `seal.ts` no longer declares (a table that rots guards
nothing), on `UNDER_VALUE_KEY` ≠ `VALUE_KEY_PURPOSES`, on a listed column that does not exist, on a wrap no rewriter
assigns (a key a rotation would leave under the old one, opening the value to the party it shuts out with any later
dump), on a rewriter assigning a value, on an empty purpose set and on a run that finds no participant at all.

**The defect that bought it.** ADR 0115: a scope key was a chain until then, so a rotation re-sealed nothing; replacing
the key moved every scope-level row in the rotation's one transaction — and the first form of that move re-sealed the
VALUES, so any rotator, a member with a pending re-key row among them, could rewrite other members' names, pictures,
verifications and item marks, a group's name, and the links' recovery copies (owner, 2026-11-02). Each value now has a
key of its own and a rotation re-wraps only that key; this guard is what keeps a rewriter from writing a value again,
and a purpose the crypto package adds, or a wrap an application stores, from being forgotten by the commit.
`validate_node_rekey_arity.py` is the same idea one level down.

**Its limits.** It proves the ASSIGNMENTS, not that every row reaches them or that the value is the staged one
(`ScopeRekeySliceTest` pins those: every staged wrap swapped in, a missing one `409 rekey_parts_missing`, a changed one
`409 rekey_parts_stale`, a staged value write `422 rekey_writes_value`). The purpose → column map is by hand — nothing in
the backend names the seal a column holds. A node's entry is under the scope key only at the scope root; the guard
cannot tell a root's row from a child's. An assignment is found by its property's name, so a value written through a
helper the rewriter calls (anything but `ROOT_ENTRY_REWRITER`) is invisible to it, as is one written by raw SQL. A
property assigned anywhere in a rewriter file counts, a comment excepted.
"""
from __future__ import annotations

import re
import sys

import backend_tree

SEAL_TS = backend_tree.ROOT / "src/packages/crypto/src/seal.ts"
TABLES = ("SEAL_PURPOSES", "FIELD_PURPOSES", "SEAL_TO_PURPOSES")
CORE_COMMIT = "me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit"
ROOT_ENTRY_REWRITER = "me.raddatz.veyq.core.node.rekey.ScopeRootEntries"
PARTICIPANT = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?ScopeRekeyParticipant\b", re.MULTILINE)
COLUMN = re.compile(r'@Column\(\s*name\s*=\s*"([a-z_]+)"')
ENTITY_CLASS = re.compile(r"^\s*(?:open\s+)?class\s+(\w+)\s*:")
PROPERTY = re.compile(r"\bva[rl]\s+(\w+)")
LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
PURPOSE_KEY = re.compile(r"^\s{2}(?:'([a-z0-9-]+)'|([a-z0-9]+))\s*:", re.MULTILINE)
VALUE_KEY_LIST = re.compile(r"export const VALUE_KEY_PURPOSES = \[(.*?)\] as const", re.DOTALL)
QUOTED = re.compile(r"'([a-z0-9-]+)'")
# An assignment to a property: `x.prop = …` or `x.prop += …` (never `==`) — and, inside a bulk update's statement
# (`Entity.update("…")`), each `prop = …` before its `where`: a query's WHERE clause assigns nothing.
ASSIGNED = re.compile(r"\.(\w+)\s*[-+*/]?=(?!=)")
BULK_UPDATE = re.compile(r'\.update\(\s*"([^"]*)"')
SET_PART = re.compile(r"\s+where\s+", re.IGNORECASE)
SET_ASSIGNED = re.compile(r"\b(\w+)\s*=")

# The values under a value key of their own (seal.ts VALUE_KEY_PURPOSES): where each value is stored, and where its key's
# wrap is — the one column a rotation rewrites.
UNDER_VALUE_KEY = {
    "group-name": {"values": ["ScopeEntity.encrypted_name"], "wraps": ["ScopeEntity.wrapped_name_value_key"]},
    "space-icon": {"values": ["ScopeEntity.sealed_icon"], "wraps": ["ScopeEntity.wrapped_icon_value_key"]},
    # K_share is a key: under a value key too, so a substituted wrap opens no K_share instead of a rotator-chosen one.
    "share-key-recovery": {"values": ["ShareEntity.sealed_share_key"], "wraps": ["ShareEntity.wrapped_recovery_value_key"]},
    "member-name": {"values": ["TenantProfileEntity.sealed_name"], "wraps": ["TenantProfileEntity.wrapped_name_value_key"]},
    "member-avatar": {"values": ["TenantProfileEntity.sealed_avatar"], "wraps": ["TenantProfileEntity.wrapped_avatar_value_key"]},
    "member-join-record": {
        "values": ["TenantProfileEntity.sealed_join_record"], "wraps": ["TenantProfileEntity.wrapped_join_record_value_key"],
    },
    "member-verification": {"values": ["VerificationEntity.sealed_statement"], "wraps": ["VerificationEntity.wrapped_value_key"]},
    # Memories' item marks: rewritten by its participant (ADR 0068, no node key).
    "item-favorites": {"values": ["ItemFavoritesEntity.ciphertext"], "wraps": ["ItemFavoritesEntity.wrapped_value_key"]},
}

# The exemption: under the scope key itself and re-sealed by a rotation, in ROOT_ENTRY_REWRITER.
RESEALED_UNDER_SCOPE_KEY = {
    # A node's entry is under its parent's key, which at the scope root is the scope key itself (ADR 0080 Decision 2):
    # the root's key wrap, its name and the slug token (a keyed MAC under the same key, no seal of its own).
    "content-key": {
        "columns": ["NodeEntity.wrapped_content_key"],
        "reason": "a scope-root node's key wrap: any member may write a root entry (ADR 0111 decision 11), so a rotator "
                  "re-sealing it can do nothing a member could not",
    },
    "node-name": {
        "columns": ["NodeEntity.encrypted_name", "NodeEntity.slug_token"],
        "reason": "a scope-root node's name, and its slug token — a keyed MAC under the scope key that must move with it",
    },
}

# The wrap itself: under the scope key, its columns every UNDER_VALUE_KEY `wraps` entry.
WRAP_PURPOSE = "value-key"

# Column properties a rewriter may assign that are no wrap — each with why it is no value.
REWRITER_METADATA = {
    "avatarRevision": "TenantProfileEntity's picture revision moves with the picture's new wrap, so a reader's cached pair "
                      "is fetched again (ADR 0084's ETag) — a counter, no value",
    "rekeyDue": "every node of the scope marked due for its own re-key (ADR 0114 decision 4) — a flag, no value",
}

# Sealed under another key — what that key is, and why a rotation's scope commit leaves the value.
NOT_UNDER_SCOPE_KEY = {
    "scope-grant": "the scope key itself sealed to a member's (or an open invite's) identity, not a value under it: the "
                   "rotation wraps the NEW key to each (`ScopesStore.rotate`'s grants, `rotation_incomplete` when one "
                   "is missing, ADR 0111 decision 8)",
    "group-name-key": "the group name's value key sealed to a pending invitee's identity, not under the scope key: a "
                      "rotation leaves it, a rename replaces it with the new name's key (`ScopesStore.renameGroup`)",
    "blob-key": "a K_blob under its node's key — the node's re-key re-wraps it (validate_node_rekey_arity.py)",
    "drop-private-key": "under the drop box's node key — the node's re-key replaces it",
    "favorites": "a member's own pin set under their Master Key (ADR 0068): no other member opens it",
    "identity-pins": "under the account's Master Key",
    "identity-pin": "under the account's Master Key",
    "device-register": "under the account's Master Key",
    "share-key": "under the link's own K_share, which only a re-issue replaces",
    "share-password": "under K_share",
    "drop-public-key": "under K_share",
    "blob-meta": "under the blob's own K_blob, which a re-key keeps (ADR 0109 point 3)",
    "master-key": "under an unlock KEK or the Master Key",
    "identity-priv": "under the Master Key",
    "report-share": "under a report's fresh K_report: what was reported keeps opening (ADR 0115 decision 8)",
    "report-link": "under K_report",
    "expense-entry-key": "an Expenses revision's K_rev under its Pot's node key — the node's re-key re-wraps it",
    "moderation-statement-facts": "under a statement's K_facts",
    "invite-link": "under a KEK derived from the invite link's code or password",
    "node-meta": "under the node's own key — the node's re-key re-seals it",
    "media-meta": "under the node's key or a guest's K_up — Memories' node re-key participant",
    "media-exif": "under the node's key or a guest's K_up — Memories' node re-key participant",
    "bucket-name": "under the node's key or a guest's K_up — Memories' node re-key participant",
    "share-identity-name": "under the node's key (a READ_WRITE identity's) — the node's re-key re-seals it",
    "expense-entry": "under the revision's own K_rev, never a scope or node key (ADR 0108 decision 4)",
    "share-memory-name": "under K_share",
    "share-note": "under K_share",
    "share-name": "under K_share",
    "upload-identity-key": "a guest's K_up sealed to the node's drop public key — the node's re-key re-seals it",
    "pairing-grant": "to a single-use ephemeral key",
    "moderation-grant": "the instance moderation key sealed to a moderator, replaced by its own epochs (ADR 0099)",
    "moderation-report": "to the moderation epoch's public key",
    "moderation-report-contact": "to the moderation epoch's public key",
    "moderation-note": "to the moderation epoch's public key",
    "moderation-facts-key": "a statement's K_facts sealed to a member's identity",
    "moderation-objection": "to the moderation epoch's public key",
    "moderation-order": "to the moderation epoch's public key",
    "moderation-step-facts": "to the moderation epoch's public key",
}


def strip_comments(text: str) -> str:
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def purposes() -> tuple[set[str], set[str] | None, list[str]]:
    """Every purpose `seal.ts` declares in its three tables, its VALUE_KEY_PURPOSES (None when not found), and the
    tables it could not find."""
    text = strip_comments(SEAL_TS.read_text(encoding="utf-8"))
    found: set[str] = set()
    missing: list[str] = []
    for table in TABLES:
        m = re.search(rf"export const {table} = \{{(.*?)\n\}} as const", text, re.DOTALL)
        if not m:
            missing.append(table)
            continue
        found |= {a or b for a, b in PURPOSE_KEY.findall(m.group(1))}
    listed = VALUE_KEY_LIST.search(text)
    return found, (set(QUOTED.findall(listed.group(1))) if listed else None), missing


def columns() -> dict[str, str]:
    """`Entity.column` → its Kotlin property, for every `@Column` of backend main Kotlin."""
    found: dict[str, str] = {}
    for path in backend_tree.main_kotlin():
        enclosing = None
        lines = path.read_text(encoding="utf-8").splitlines()
        for i, line in enumerate(lines):
            if klass := ENTITY_CLASS.match(line):
                enclosing = klass.group(1)
            if (column := COLUMN.search(line)) and enclosing:
                prop = PROPERTY.search(line) or next((m for m in (PROPERTY.search(x) for x in lines[i + 1:i + 4]) if m), None)
                found[f"{enclosing}.{column.group(1)}"] = prop.group(1) if prop else ""
    return found


def assigned(text: str) -> set[str]:
    """Every property name [text] assigns (comments already stripped): by `.prop =`, and in a bulk update's SET part."""
    found = set(ASSIGNED.findall(text))
    for statement in BULK_UPDATE.findall(text):
        found |= set(SET_ASSIGNED.findall(SET_PART.split(statement, maxsplit=1)[0]))
    return found


def main() -> int:
    errors: list[str] = []
    declared, value_keyed, missing_tables = purposes()
    for table in missing_tables:
        errors.append(f"`{table}` not found in {SEAL_TS.relative_to(backend_tree.ROOT)} — the table's shape drifted; re-point this guard.")
    if not declared:
        errors.append("no seal purpose was read from seal.ts — an empty set is vacuously green.")
    if value_keyed is None:
        errors.append("`VALUE_KEY_PURPOSES` not found in seal.ts — the list's shape drifted; re-point this guard.")
    tables = {
        "UNDER_VALUE_KEY": set(UNDER_VALUE_KEY), "RESEALED_UNDER_SCOPE_KEY": set(RESEALED_UNDER_SCOPE_KEY),
        "WRAP_PURPOSE": {WRAP_PURPOSE}, "NOT_UNDER_SCOPE_KEY": set(NOT_UNDER_SCOPE_KEY),
    }
    classified: set[str] = set().union(*tables.values())
    for p in sorted(classified):
        homes = [name for name, members in tables.items() if p in members]
        if len(homes) > 1:
            errors.append(f"purpose `{p}` is classified in {' and '.join(homes)} — decide which.")
    for p in sorted(declared - classified):
        errors.append(
            f"purpose `{p}` (seal.ts) is unclassified: is what it seals a scope-level value that has an author? Then it is "
            f"sealed under a value key of its own (seal.ts VALUE_KEY_PURPOSES, ADR 0115): add it to UNDER_VALUE_KEY with "
            f"its value and wrap columns, and rewrite the wrap — never the value — in `ScopeRekeyCommit` or the owning "
            f"application's `ScopeRekeyParticipant`. If not, add it to NOT_UNDER_SCOPE_KEY with the key it is under."
        )
    if declared:
        for p in sorted(classified - declared):
            errors.append(f"purpose `{p}` is classified but seal.ts declares no such purpose any more — drop the entry.")
    if value_keyed is not None:
        for p in sorted(set(UNDER_VALUE_KEY) - value_keyed):
            errors.append(f"purpose `{p}` is UNDER_VALUE_KEY here but not in seal.ts VALUE_KEY_PURPOSES — the crypto package "
                          f"would let it be addressed as no value key's value; add it there or reclassify it here.")
        for p in sorted(value_keyed - set(UNDER_VALUE_KEY)):
            errors.append(f"purpose `{p}` is in seal.ts VALUE_KEY_PURPOSES but not UNDER_VALUE_KEY here — its wrap would be "
                          f"left under the replaced key; classify it with its value and wrap columns.")
    for p, reason in NOT_UNDER_SCOPE_KEY.items():
        if not reason.strip():
            errors.append(f"purpose `{p}` is excluded with no reason.")
    for p, entry in RESEALED_UNDER_SCOPE_KEY.items():
        if not entry["reason"].strip():
            errors.append(f"purpose `{p}` is exempt with no reason.")

    rewriters: dict[str, str] = {}
    core = backend_tree.source_file(CORE_COMMIT)
    if core.is_file():
        rewriters[core.stem] = strip_comments(core.read_text(encoding="utf-8"))
    else:
        errors.append(f"core's scope commit `{CORE_COMMIT}` is missing — re-point this guard, never skip it.")
    for path in backend_tree.main_kotlin():
        text = path.read_text(encoding="utf-8")
        if PARTICIPANT.search(text):
            rewriters[path.stem] = strip_comments(text)
    if not any(k != core.stem for k in rewriters):
        errors.append("no class implementing `ScopeRekeyParticipant` was found: Memories' item marks would stay under the "
                      "old scope key after every rotation.")
    root_file = backend_tree.source_file(ROOT_ENTRY_REWRITER)
    root_text = strip_comments(root_file.read_text(encoding="utf-8")) if root_file.is_file() else None
    if root_text is None:
        errors.append(f"the root-entry re-seal `{ROOT_ENTRY_REWRITER}` is missing — re-point this guard, never skip it.")

    cols = columns()
    if not cols:
        errors.append("no @Column was found — the annotation shape drifted; an empty set is vacuously green.")

    def prop_of(col: str, purpose: str) -> str | None:
        if col not in cols:
            errors.append(f"`{col}` (purpose `{purpose}`) is no @Column of backend main Kotlin any more — re-point the entry.")
            return None
        if not cols[col]:
            errors.append(f"`{col}`: no Kotlin property found beside its @Column — the guard cannot read its writer.")
            return None
        return cols[col]

    covered: list[str] = []
    wrap_props: set[str] = set()
    value_entities: set[str] = set()
    for purpose, entry in sorted(UNDER_VALUE_KEY.items()):
        if not entry["values"] or not entry["wraps"]:
            errors.append(f"purpose `{purpose}` is under a value key but names no value or no wrap column.")
        for col in entry["values"]:
            prop_of(col, purpose)
            value_entities.add(col.split(".")[0])
        for col in entry["wraps"]:
            prop = prop_of(col, purpose)
            if prop is None:
                continue
            wrap_props.add(prop)
            entity = col.split(".")[0]
            hits = [name for name, text in rewriters.items() if re.search(rf"\b{entity}\b", text) and prop in assigned(text)]
            if not hits:
                errors.append(
                    f"`{col}` (`{prop}`, purpose `{purpose}`) wraps a value key under the scope key and no rotation "
                    f"rewrites it — neither core's `ScopeRekeyCommit` nor a `ScopeRekeyParticipant`. After a rotation it "
                    f"would stay under the replaced key (ADR 0115 decision 2): re-wrap it in the commit, staged and fail closed."
                )
                continue
            covered.append(f"{col} ({'/'.join(hits)})")

    # A rotation writes only wraps: every column property a rewriter assigns is a wrap (or named metadata).
    column_props = set(cols.values()) - {""}
    allowed = wrap_props | set(REWRITER_METADATA)
    for name, text in sorted(rewriters.items()):
        for prop in sorted((assigned(text) & column_props) - allowed):
            errors.append(
                f"`{name}` assigns `{prop}`, a column that is no value key's wrap — a rotation re-wraps keys and never "
                f"rewrites a value (ADR 0115). Re-wrap the value's key instead, or — for a counter that is no value — name "
                f"it in REWRITER_METADATA with its reason."
            )

    # The exemption, written in its own file — which names no entity holding a value, so its assignments are a node's.
    if root_text is not None:
        if f"{root_file.stem}.reseal" not in rewriters.get(core.stem, ""):
            errors.append(f"core's scope commit no longer calls `{root_file.stem}.reseal`: the scope-root entries would stay "
                          f"under the replaced key.")
        for entity in sorted(value_entities):
            if re.search(rf"\b{entity}\b", root_text):
                errors.append(f"`{root_file.stem}` names `{entity}`, which holds a value: the root-entry re-seal is exempt "
                              f"from 'a rotation writes only wraps' only while it can write nothing but a node's entry.")
        for purpose, entry in sorted(RESEALED_UNDER_SCOPE_KEY.items()):
            for col in entry["columns"]:
                prop = prop_of(col, purpose)
                if prop is None:
                    continue
                if prop not in assigned(root_text):
                    errors.append(f"`{col}` (`{prop}`, purpose `{purpose}`) is under the scope key and `{root_file.stem}` "
                                  f"no longer re-seals it.")
                    continue
                covered.append(f"{col} ({root_file.stem}, re-sealed)")
    if errors:
        print("SCOPE REKEY ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(declared)} seal purpose(s) classified, {len(UNDER_VALUE_KEY)} under a value key of their own; "
          f"{len(covered)} scope-key column(s) moved by a rotation, and no rewriter assigns a value: " + "; ".join(covered))
    return 0


if __name__ == "__main__":
    sys.exit(main())
