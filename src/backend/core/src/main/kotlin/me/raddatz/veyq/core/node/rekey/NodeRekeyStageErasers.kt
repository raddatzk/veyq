package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.node.NodeSubtreeEraser
import me.raddatz.veyq.core.scope.ScopeEraser
import jakarta.enterprise.context.ApplicationScoped

/**
 * The staged re-key values of an erased node subtree (the trash purge, ADR 0061): a stage names its node and is
 * nothing without it. Phase 350, anywhere among the node-row erasers: the table has no foreign key and names no blob,
 * so its place in the order is free; it names no guest identity either.
 */
@ApplicationScoped
class NodeRekeyStageNodeSubtreeEraser : NodeSubtreeEraser {
    override val phase = 350

    override fun erase(scopeId: String, nodeIds: List<String>): Set<String> {
        NodeRekeyStageEntity.delete("scopeId = ?1 and nodeId in ?2", scopeId, nodeIds)
        return emptySet()
    }
}

/** The staged re-key values of an erased scope. Phase 650: no foreign key either way, so before the node rows. */
@ApplicationScoped
class NodeRekeyStageScopeEraser : ScopeEraser {
    override val phase = 650

    override fun eraseScope(scopeId: String) {
        NodeRekeyStageEntity.delete("scopeId", scopeId)
    }
}
