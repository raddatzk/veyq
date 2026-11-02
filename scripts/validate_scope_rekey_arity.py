#!/usr/bin/env python3
"""Every seal purpose is classified for a scope key's replacement, and every column under the scope key is rewritten by
a rotation's commit (ADR 0115 decisions 1-2).

**What it asserts.** Its subject is derived from the crypto package on every run, never listed: every seal purpose
`src/packages/crypto/src/seal.ts` declares — the keys of `SEAL_PURPOSES`, `FIELD_PURPOSES` and `SEAL_TO_PURPOSES`, read
from the file with its comments stripped. Each purpose is in exactly one of the two tables below:

  - `UNDER_SCOPE_KEY`: a purpose whose ciphertext is (or, for a node's entry, at the scope root is) sealed under the
    scope key, with the backend columns `Entity.column` it is stored in. Each column must exist (an `@Column(name =
    "...")` in a main Kotlin class of that name, read with the Kotlin property it maps) and its property must be
    **assigned** (`.prop =`, or `prop = :` / `prop = ?` in a bulk update) in core's scope commit
    (`me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit`) or in a class implementing `ScopeRekeyParticipant` (an
    application's part of the commit) — a file that also names the entity, since one property name can be two
    entities' (`encryptedName` is a node's and a group's).
  - `NOT_UNDER_SCOPE_KEY`: a purpose sealed under another key, with that key and why a rotation leaves it.

Red on a purpose in neither table or in both (a new purpose is red until someone decides whether a rotation must
re-seal it), on a classified purpose `seal.ts` no longer declares (a table that rots guards nothing), on a listed column
that does not exist or that no rewriter assigns (a value a rotation would leave under the old key, readable to the
party it shuts out with any later dump), on an empty purpose set and on a run that finds no participant at all.

**The defect that bought it.** ADR 0115: a scope key was a chain until then — every epoch's key sealed its
predecessor, so a rotation re-sealed nothing and everything ever sealed under `E_0 … E_n` stayed so in the live store,
opened by a removed member's key from any later dump. Replacing the key moves every scope-level row in the rotation's
one transaction, and fails closed on a row without a staged value — but only for the rows the commit knows of. This
guard is what keeps a purpose the crypto package adds, or a column an application stores under the scope key, from
being forgotten by the commit: `validate_node_rekey_arity.py` is the same idea one level down, and the recovery copy's
"left at its epoch" exclusion moved from there to here.

**Its limits.** It proves the ASSIGNMENT exists, not that every row reaches it or that the value is the staged one
(`ScopeRekeySliceTest` pins those: every staged row swapped in, a missing one `409 rekey_parts_missing`, a changed one
`409 rekey_parts_stale`). The purpose → column map is by hand — nothing in the backend names the seal a column holds,
and a column filed under the wrong purpose, or a purpose's second column left out of its list, is invisible to it. A
node's entry (`content-key`, `node-name` and its slug MAC) is under the scope key only at the scope root; the guard
cannot tell a root's row from a child's, so it holds the rewrite of the columns, not of the rows. A property assigned
anywhere in a rewriter file counts, a comment excepted.
"""
from __future__ import annotations

import re
import sys

import backend_tree

SEAL_TS = backend_tree.ROOT / "src/packages/crypto/src/seal.ts"
TABLES = ("SEAL_PURPOSES", "FIELD_PURPOSES", "SEAL_TO_PURPOSES")
CORE_COMMIT = "me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit"
PARTICIPANT = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?ScopeRekeyParticipant\b", re.MULTILINE)
COLUMN = re.compile(r'@Column\(\s*name\s*=\s*"([a-z_]+)"')
ENTITY_CLASS = re.compile(r"^\s*(?:open\s+)?class\s+(\w+)\s*:")
PROPERTY = re.compile(r"\bva[rl]\s+(\w+)")
LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
PURPOSE_KEY = re.compile(r"^\s{2}(?:'([a-z0-9-]+)'|([a-z0-9]+))\s*:", re.MULTILINE)

# Sealed under the scope key — each with the columns a rotation's commit must rewrite (ADR 0115 decision 2's table).
UNDER_SCOPE_KEY = {
    # A node's entry is under its parent's key, which at the scope root is the scope key itself (ADR 0080 Decision 2):
    # the root's key wrap, its name and the slug token (a keyed MAC under the same key, no seal of its own).
    "content-key": ["NodeEntity.wrapped_content_key"],
    "node-name": ["NodeEntity.encrypted_name", "NodeEntity.slug_token"],
    "share-key-recovery": ["ShareEntity.sealed_share_key"],
    "group-name": ["ScopeEntity.encrypted_name"],
    "space-icon": ["ScopeEntity.sealed_icon"],
    # K_name is derived from the scope key, so a pending invitee's copy of it is the old key's until re-sealed.
    "group-name-key": ["MembershipEntity.sealed_name_key"],
    "member-name": ["TenantProfileEntity.sealed_name"],
    "member-avatar": ["TenantProfileEntity.sealed_avatar"],
    "member-join-record": ["TenantProfileEntity.sealed_join_record"],
    "member-verification": ["VerificationEntity.sealed_statement"],
    # Memories' item marks: under the scope key itself, no node key (ADR 0068), re-sealed by its participant.
    "item-favorites": ["ItemFavoritesEntity.ciphertext"],
}

# Sealed under another key — what that key is, and why a rotation's scope commit leaves the value.
NOT_UNDER_SCOPE_KEY = {
    "scope-grant": "the scope key itself sealed to a member's (or an open invite's) identity, not a value under it: the "
                   "rotation wraps the NEW key to each (`ScopesStore.rotate`'s grants, `rotation_incomplete` when one "
                   "is missing, ADR 0111 decision 8)",
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


def purposes() -> tuple[set[str], list[str]]:
    """Every purpose `seal.ts` declares in its three tables, and the tables it could not find."""
    text = strip_comments(SEAL_TS.read_text(encoding="utf-8"))
    found: set[str] = set()
    missing: list[str] = []
    for table in TABLES:
        m = re.search(rf"export const {table} = \{{(.*?)\n\}} as const", text, re.DOTALL)
        if not m:
            missing.append(table)
            continue
        found |= {a or b for a, b in PURPOSE_KEY.findall(m.group(1))}
    return found, missing


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


def main() -> int:
    errors: list[str] = []
    declared, missing_tables = purposes()
    for table in missing_tables:
        errors.append(f"`{table}` not found in {SEAL_TS.relative_to(backend_tree.ROOT)} — the table's shape drifted; re-point this guard.")
    if not declared:
        errors.append("no seal purpose was read from seal.ts — an empty set is vacuously green.")
    both = set(UNDER_SCOPE_KEY) & set(NOT_UNDER_SCOPE_KEY)
    for p in sorted(both):
        errors.append(f"purpose `{p}` is classified both under and not under the scope key — decide which.")
    for p in sorted(declared - set(UNDER_SCOPE_KEY) - set(NOT_UNDER_SCOPE_KEY)):
        errors.append(
            f"purpose `{p}` (seal.ts) is unclassified: is what it seals under the scope key? If so, a rotation must re-seal "
            f"it (ADR 0115 decision 2): add it to UNDER_SCOPE_KEY with the column(s) it is stored in and rewrite them in "
            f"`ScopeRekeyCommit` or the owning application's `ScopeRekeyParticipant`. If not, add it to NOT_UNDER_SCOPE_KEY "
            f"with the key it is under."
        )
    for p in sorted((set(UNDER_SCOPE_KEY) | set(NOT_UNDER_SCOPE_KEY)) - declared):
        if declared:
            errors.append(f"purpose `{p}` is classified but seal.ts declares no such purpose any more — drop the entry.")
    for p, reason in NOT_UNDER_SCOPE_KEY.items():
        if not reason.strip():
            errors.append(f"purpose `{p}` is excluded with no reason.")

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

    cols = columns()
    if not cols:
        errors.append("no @Column was found — the annotation shape drifted; an empty set is vacuously green.")
    covered: list[str] = []
    for purpose, targets in sorted(UNDER_SCOPE_KEY.items()):
        if not targets:
            errors.append(f"purpose `{purpose}` is under the scope key but names no column.")
        for col in targets:
            if col not in cols:
                errors.append(f"`{col}` (purpose `{purpose}`) is no @Column of backend main Kotlin any more — re-point the entry.")
                continue
            prop = cols[col]
            if not prop:
                errors.append(f"`{col}`: no Kotlin property found beside its @Column — the guard cannot read its writer.")
                continue
            assigned = re.compile(rf"\.{prop}\s*=(?!=)|\b{prop}\s*=\s*[:?]")
            entity = col.split(".")[0]
            hits = [name for name, text in rewriters.items() if re.search(rf"\b{entity}\b", text) and assigned.search(text)]
            if not hits:
                errors.append(
                    f"`{col}` (`{prop}`, purpose `{purpose}`) is sealed under the scope key and no rotation rewrites it — "
                    f"neither core's `ScopeRekeyCommit` nor a `ScopeRekeyParticipant`. After a rotation it would stay under "
                    f"the replaced key (ADR 0115 decision 2): re-seal it in the commit, staged and fail closed."
                )
                continue
            covered.append(f"{col} ({'/'.join(hits)})")
    if errors:
        print("SCOPE REKEY ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(declared)} seal purpose(s) classified, {len(covered)} scope-key column(s) rewritten by a rotation: "
          + "; ".join(covered))
    return 0


if __name__ == "__main__":
    sys.exit(main())
