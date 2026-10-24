package me.raddatz.veyq.expenses

import me.raddatz.veyq.core.node.NodeEntity
import me.raddatz.veyq.core.node.NodeKindRules
import me.raddatz.veyq.core.node.ParentSlot
import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.sharing.ShareEntity
import me.raddatz.veyq.core.sharing.ShareRoots
import jakarta.enterprise.context.ApplicationScoped

/** The node kind Expenses owns (ADR 0108 decision 1) — a value of `veyq_node.kind` core does not interpret. */
object ExpensesNodeKinds {
    /**
     * A Pot (*Kasse*): one node whose sealed meta holds its base currency and its own categories, and whose participants
     * and entries are records of sealed revisions in Expenses' own table ([me.raddatz.veyq.expenses.pot.PotRevisionEntity]),
     * never child nodes. It has no children, lives at the root or in a `FOLDER`, and is a share root with `READ` or
     * `READ_WRITE` ([ExpensesShareRoots]).
     */
    const val POT = "EXPENSES_POT"
}

/**
 * Where a Pot lives in core's tree (ADR 0108 decision 2): at the root or under a `FOLDER`, as a Depot file — never under
 * another kind, and nothing under it (no rule admits a Pot as a parent). Only a member creates one: Expenses declares no
 * `GuestCreatableKinds`, so a folder link's guest creates none (owner 2026-10-14), and no `GuestNodeRules`, so a link
 * rooted on a Pot has no tree rights inside it (the container default, `illegal_scope`). `ModuleCompositionTest` pins
 * the partition across core and every application.
 */
@ApplicationScoped
class ExpensesNodeKindRules : NodeKindRules {
    override fun handles(kind: String): Boolean = kind == ExpensesNodeKinds.POT

    override fun parentAllows(kind: String, parent: ParentSlot): Boolean =
        handles(kind) && (parent is ParentSlot.Root || (parent is ParentSlot.Node && parent.kind == NodeEntity.FOLDER))

    /** A Pot is Expenses': switched off, no Pot is created, renamed, moved or linked (uc.platform.switch-application-off). */
    override fun applicationOf(kind: String): String? = if (handles(kind)) CapabilityIds.EXPENSES else null
}

/**
 * Which share roles a Pot takes (ADR 0108 decision 2, CR decision 6): `READ` (see the ledger) and `READ_WRITE` (and
 * append revisions to it). **Never `WRITE_ONLY`**: a write-only guest reads nothing, so it has no balance to write
 * against and no heads to name — `illegal_scope`, fail closed.
 */
@ApplicationScoped
class ExpensesShareRoots : ShareRoots {
    override fun roles(kind: String): Set<String>? =
        if (kind == ExpensesNodeKinds.POT) setOf(ShareEntity.READ, ShareEntity.READ_WRITE) else null
}
