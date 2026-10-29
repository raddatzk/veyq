package me.raddatz.veyq.core.node.rekey

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * One staged value of a node's re-key (ADR 0109 point 2): a ciphertext the commit of [rekeyId] swaps
 * into the row [itemId] names, in the column [field] names, of the owner [part] (`core`, or an application's name).
 * [revision] is the row's revision as the re-keyer read it, where the row has one (a child's placement revision, a
 * medium's meta revision). Nothing serves or reads a staged value but the commit; a stage no commit took is deleted
 * after [NodeRekeyStage.TTL] ([createdAt] is that reader). Operator-blind: the same ciphertext the commit carries.
 */
@Entity
@Table(name = "veyq_node_rekey_stage")
@DynamicUpdate
class NodeRekeyStageEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "rekey_id", nullable = false, columnDefinition = "uuid")
    lateinit var rekeyId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "node_id", nullable = false, columnDefinition = "uuid")
    lateinit var nodeId: String

    @Column(name = "part", nullable = false, length = 32)
    lateinit var part: String

    @Column(name = "item_id", nullable = false, length = 64)
    lateinit var itemId: String

    @Column(name = "field", nullable = false, length = 32)
    lateinit var field: String

    @Column(name = "stage_value", nullable = false)
    lateinit var stageValue: String

    @Column(name = "revision")
    var revision: Long? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<NodeRekeyStageEntity, String>
}
