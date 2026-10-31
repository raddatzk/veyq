#!/usr/bin/env python3
"""Every column sealed under a node's key is rewritten by a node's re-key (ADR 0109 point 2, CR decision 2).

**What it asserts.** Its subject is derived from the entities on every run, never listed: every `@Column` in the
backend's hand-written main Kotlin whose name says it holds ciphertext or a wrap — `wrapped_*`, `sealed_*`,
`encrypted_*`, a share's `wrap` — and `slug_token` (a keyed MAC under the parent's key, recomputed with the key), read
with the entity class and the Kotlin property it maps. Each such column is either

  - **rewritten** by the re-key: its property is assigned (`.prop =`, or `prop = :` / `prop = ?` in a bulk update) in
    core's commit (`me.raddatz.veyq.core.node.rekey.RekeyStore`) or in a class implementing `NodeRekeyParticipant` (an
    application's part of the commit), or
  - a named **exclusion** below: a column sealed under another key than a node's, each with its reason.

Red on a column that is neither (a field sealed under a node key that a rotation would leave readable to the excluded
party), on an exclusion naming a column that no longer exists (a list that rots guards nothing), on an empty subject
and on a run that finds no rewriter at all.

**The defect that bought it.** Inbox #518: a rotation moved `K_SC` only, so every field sealed under a node key — a new
child's key and name, a new medium's `K_blob` wrap, its meta and EXIF — stayed under a key the excluded party holds. The
re-key re-seals every such field; this guard is what keeps the next one an application adds from being forgotten.

**Its limits.** It proves the ASSIGNMENT exists, not that it is reached for every row (the slice tests of the commit
and of each participant pin that, rows missing → `409 rekey_parts_missing`), nor that the value assigned is the staged
one. It reads columns by NAME: a node-key column named outside the prefixes (`ciphertext`, `blob`) is invisible to it —
`validate_guard_gaps.py` case 93 pins that gap. A property assigned anywhere in a rewriter file counts, a comment excepted.
"""
from __future__ import annotations

import re
import sys

import backend_tree

COLUMN = re.compile(r'@Column\(\s*name\s*=\s*"((?:wrapped|sealed|encrypted)_[a-z_]+|wrap|slug_token)"')
ENTITY_CLASS = re.compile(r"^\s*(?:open\s+)?class\s+(\w+)\s*:")
PROPERTY = re.compile(r"\bva[rl]\s+(\w+)")
CORE_COMMIT = "me.raddatz.veyq.core.node.rekey.RekeyStore"
PARTICIPANT = re.compile(r"^\s*(?:\w+\s+)*class\s+\w+[^{=]*:\s*(?:[\w.,\s]*,\s*)?NodeRekeyParticipant\b", re.MULTILINE)
LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)

# Columns sealed under another key than a node's — the re-key leaves them, and why.
EXCLUSIONS = {
    "PotRevisionEntity.sealed_body": "an Expenses revision's body under its own random K_rev (`expense-entry`), never "
                                     "a node key: a re-key re-wraps K_rev (`wrapped_revision_key`) and leaves every "
                                     "body and its digest byte-identical (ADR 0108 decision 4)",
    "BlobEntity.wrapped_meta":"`blob-meta` under the blob's own K_blob, which a re-key keeps (ADR 0109 point 3); write-once",
    "UploadSessionEntity.wrapped_blob_key": "an open upload's wrap lands only through a completion that states the node's "
                                            "epoch, refused `stale_epoch` once the node was re-keyed (ADR 0109 point 6)",
    "UploadSessionEntity.wrapped_meta": "`blob-meta` under K_blob, as BlobEntity.wrapped_meta",
    "ShareEntity.sealed_name": "under the link's K_share, which only a re-issue replaces (ADR 0109 point 4)",
    "ShareEntity.sealed_note": "under K_share",
    "ShareEntity.sealed_share_name": "under K_share",
    "ShareEntity.sealed_password": "under K_share",
    "ShareEntity.sealed_share_key": "the link's recovery copy under K_SC at its epoch, left there so `reissueRequired` "
                                    "stays true (ADR 0109 point 4)",
    "ShareEntity.sealed_share_key_epoch": "the epoch of that recovery copy, a number",
    "ScopeEntity.encrypted_name": "the group's name under K_name, derived from the scope epoch key (ADR 0027)",
    "ScopeEntity.sealed_icon": "the space icon under K_icon, derived from the scope epoch key",
    "ScopeEpochEntity.wrapped_prev": "the epoch chain link: E_n under E_n+1, written by the rotation itself",
    "MembershipEntity.wrapped_grant": "the scope key sealed to a member's identity, written by the rotation",
    "MembershipEntity.sealed_name_key": "the group-name key sealed to a member's identity",
    "MasterKeyWrapEntity.wrapped_key": "an account's Master Key under an unlock KEK",
    "VeyqUser.wrapped_identity_private_key": "the identity under the Master Key",
    "DeviceRegisterEntity.sealed_blob": "the device register under the Master Key",
    "IdentityPinsEntity.sealed_blob": "the identity-pin head under the Master Key",
    "IdentityPinEntryEntity.sealed_entry": "an identity pin under the Master Key",
    "TenantProfileEntity.sealed_name": "a member's real name under K_tenant (a scope epoch key)",
    "TenantProfileEntity.sealed_join_record": "a member's join record under K_tenant (a scope epoch key), ADR 0111",
    "VerificationEntity.sealed_statement": "a member's verification under K_tenant (a scope epoch key, the epoch it names), ADR 0111 decision 12",
    "TenantInviteEntity.wrapped_grant": "a tenant epoch key sealed to an open invite's key, written by the rotation (ADR 0111)",
    "RotationStageGrantEntity.wrapped_grant": "a scope's epoch key sealed to the identity a securing of the account will "
                                              "publish, staged until its publish applies it (cr-secure-the-account)",
    "TenantProfileEntity.sealed_avatar": "a member's picture under K_tenant",
    "JobEntity.sealed_body": "a task's body sealed to its worker",
    "JobEntity.sealed_result": "a task's result sealed to its originator",
    "ModerationReportEntity.sealed_report": "a report under its K_report (moderation key); a hold copy is a snapshot the "
                                            "re-key never touches (ADR 0109 point 8)",
    "ModerationReportEntity.sealed_contact": "under K_report",
    "ReportShareEntity.sealed_keys": "the keys a report hands over, under K_report: they keep opening what was reported "
                                     "(ADR 0109 point 8)",
    "ModerationGrantEntity.sealed_grant": "the moderation key sealed to a moderator",
    "RotationStageModerationGrantEntity.sealed_grant": "the moderation key sealed to the identity a moderator's securing of "
                                                       "the account will publish, staged until its publish applies it "
                                                       "(ADR 0099 § 4)",
    "ModerationEventEntity.sealed_text": "a moderation note under the moderation epoch key",
    "ModerationObjectionEntity.sealed_objection": "under the moderation epoch key",
    "ModerationStatementEntity.sealed_facts": "under a statement's facts key",
    "ModerationStatementEntity.sealed_member_facts": "under a statement's facts key",
    "StatementDeferralEntity.sealed_reason": "under the moderation epoch key",
    "StatementRecipientEntity.wrapped_facts_key": "a statement's facts key sealed to its recipient",
    "AuthorityOrderEntity.sealed_reference": "under the moderation epoch key",
}


def columns() -> dict[str, str]:
    """`Entity.column` → its Kotlin property, for every ciphertext-shaped column of backend main Kotlin."""
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


def strip_comments(text: str) -> str:
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))


def main() -> int:
    errors: list[str] = []
    rewriters: dict[str, str] = {}
    core = backend_tree.source_file(CORE_COMMIT)
    if core.is_file():
        rewriters[core.stem] = strip_comments(core.read_text(encoding="utf-8"))
    else:
        errors.append(f"core's re-key commit `{CORE_COMMIT}` is missing — re-point this guard, never skip it.")
    for path in backend_tree.main_kotlin():
        text = path.read_text(encoding="utf-8")
        if PARTICIPANT.search(text):
            rewriters[path.stem] = strip_comments(text)
    if not any(k != core.stem for k in rewriters):
        errors.append("no class implementing `NodeRekeyParticipant` was found: an application's node-key fields (Memories' "
                      "media meta, EXIF and blob wraps) would stay under the old key after every rotation.")
    cols = columns()
    if not cols:
        errors.append("no ciphertext-shaped @Column was found — the annotation shape drifted; an empty set is vacuously green.")
    for stale in sorted(set(EXCLUSIONS) - set(cols)):
        errors.append(f"`{stale}` is excluded but no such column exists any more — drop the entry.")
    covered: list[str] = []
    for col, prop in sorted(cols.items()):
        if col in EXCLUSIONS:
            continue
        if not prop:
            errors.append(f"`{col}`: no Kotlin property found beside its @Column — the guard cannot read its writer.")
            continue
        assigned = re.compile(rf"\.{prop}\s*=(?!=)|\b{prop}\s*=\s*[:?]")
        # The file must name the entity too: `wrappedMeta` is a node's AND a medium's property, and the node's commit
        # assigning its own must not count for the medium's.
        entity = col.split(".")[0]
        hits = [name for name, text in rewriters.items() if re.search(rf"\b{entity}\b", text) and assigned.search(text)]
        if not hits:
            errors.append(
                f"`{col}` (`{prop}`) holds ciphertext and is neither rewritten by a node's re-key (core's `RekeyStore`, a "
                f"`NodeRekeyParticipant`) nor a named exclusion. If it is sealed under a node's key, a rotation leaves it "
                f"readable to the party it shuts out (ADR 0109 point 2): rewrite it in the owning package's participant. "
                f"If it is sealed under another key, add it to EXCLUSIONS with the key and why."
            )
            continue
        covered.append(f"{col} ({'/'.join(hits)})")
    if errors:
        print("NODE REKEY ARITY VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(covered)} node-key column(s) rewritten by a re-key, {len(EXCLUSIONS)} excluded with a reason: "
          + "; ".join(covered))
    return 0


if __name__ == "__main__":
    sys.exit(main())
