package me.raddatz.veyq.core.moderation.guestwrite

import me.raddatz.veyq.core.node.NodeSubtreeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The guest-write records of an erased node subtree (ADR 0093 § 10: a row goes with what it names) — the nodes' own
 * rows; an application's items below them go with the application's eraser, which calls [GuestWriteRecords.forget]
 * too (Memories' media). Phase 400: the table has no foreign key onto a node, so any phase would do; 400 is free and
 * after the attachments (200) and counters (300). It names no identity: a record never keeps one alive
 * (`fk_guest_write_record_identity` cascades the other way). A space's own erasure needs no eraser here: every record
 * of a space names an identity of one of its links, and `ShareScopeEraser` deletes those, cascading.
 */
@ApplicationScoped
class GuestWriteRecordNodeSubtreeEraser(private val records: GuestWriteRecords) : NodeSubtreeEraser {
    override val phase = 400

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        records.forget(nodeIds)
        return emptySet()
    }
}
