package me.raddatz.veyq.core.node.rekey

import me.raddatz.veyq.core.scope.ScopeEraser
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import org.hibernate.annotations.DynamicUpdate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One staged value of a rotation's scope-level move (ADR 0115 decision 2): a value key's new wrap — or a scope-root
 * entry's re-sealed part — the rotation's commit (`rotateScope`, [ScopeRekeyCommit]) swaps into the row [itemId] names,
 * in the column [field] names, of the owner [part] (`core`, or an application's name, [ScopeRekeyParticipant.part]).
 * Never a value's own ciphertext: the commit refuses a field it does not know as a wrap (`422 rekey_writes_value`).
 * [replaced] is the wrap the rotator re-wrapped, as it read it, so the commit refuses a row that holds another (`409
 * rekey_parts_stale`) with no revision of its own; [revision] is a scope-root node's placement revision the client
 * stated — what holds a root entry instead. Nothing serves or reads a staged value but the commit; a stage no commit took
 * is deleted after [ScopeRekeyStage.TTL] ([createdAt] is that reader). Operator-blind: the same ciphertext the commit
 * carries.
 */
@Entity
@Table(name = "veyq_scope_rekey_stage")
@DynamicUpdate
class ScopeRekeyStageEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "rekey_id", nullable = false, columnDefinition = "uuid")
    lateinit var rekeyId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "part", nullable = false, length = 32)
    lateinit var part: String

    @Column(name = "item_id", nullable = false, length = 80)
    lateinit var itemId: String

    @Column(name = "field", nullable = false, length = 32)
    lateinit var field: String

    @Column(name = "stage_value", nullable = false, columnDefinition = "TEXT")
    lateinit var stageValue: String

    @Column(name = "replaced", length = 128)
    var replaced: String? = null

    @Column(name = "revision")
    var revision: Long? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ScopeRekeyStageEntity, String>
}

/**
 * The staged values of a rotation's scope-level move (ADR 0115 decision 2): the rotating client opens every value key's
 * wrap under the scope key with the old key, wraps the same key under the one it minted (one it cannot open under a key
 * it drops) and re-seals each scope-root entry, and sends them here in pages, under a re-key id it then names in
 * `rotateScope`. Core's own rows and each application's ([ScopeRekeyParticipant.part]) share the table. A row staged
 * twice is replaced.
 *
 * **The compare-and-set.** Each staged wrap carries the wrap it replaces as the rotator read it ([Value.replaced], the
 * contract's `replacedValueKey`), and the commit refuses a row that holds another by then — any write between the read
 * and the commit, the long window of a tenant's whole profile directory staged page by page: every write of a value
 * mints a fresh value key and so a fresh wrap (a seal draws its own nonce, so no two wraps are one text). A wrap is under
 * 128 characters, so the text itself is compared, no digest of it; nothing is checked at the stage. A scope-root entry
 * is held by the placement revision the rotator states instead ([Value.revision]), which every write of an entry moves.
 */
@ApplicationScoped
class ScopeRekeyStage {

    /** Which row and column a staged value is for. */
    data class Key(val itemId: String, val field: String)

    /** A staged value with the wrap it replaces ([replaced]) — or, for a root entry, the placement revision it was
     *  re-sealed against ([revision]). */
    data class Value(val value: String, val replaced: String? = null, val revision: Long? = null)

    /** Stage [values] of [part] for [scopeId] under [rekeyId]; first deletes every stage older than [TTL] (any scope's —
     *  the one reader of `created_at`). */
    @Transactional
    fun put(rekeyId: String, scopeId: String, part: String, values: Map<Key, Value>, now: Instant = Instant.now()) {
        ScopeRekeyStageEntity.delete("createdAt < ?1", now.minus(TTL))
        if (values.isEmpty()) return
        // A row's values come in one entry (a member's three fields, a root's entry), so a later stage of the row
        // replaces all of them: a field it no longer carries is not left behind from the first.
        values.keys.map { it.itemId }.distinct().chunked(CHUNK).forEach { items ->
            ScopeRekeyStageEntity.delete("rekeyId = ?1 and part = ?2 and itemId in ?3", rekeyId, part, items)
        }
        for ((key, v) in values) {
            ScopeRekeyStageEntity().apply {
                id = UUID.randomUUID().toString()
                this.rekeyId = rekeyId
                this.scopeId = scopeId
                this.part = part
                itemId = key.itemId
                field = key.field
                stageValue = v.value
                replaced = v.replaced
                revision = v.revision
                createdAt = now
            }.persist()
        }
    }

    /** What [rekeyId] staged of [part] for [scopeId] — a stage under another scope's id is not this scope's. */
    fun read(rekeyId: String, scopeId: String, part: String): Map<Key, Value> =
        ScopeRekeyStageEntity.find("rekeyId = ?1 and scopeId = ?2 and part = ?3", rekeyId, scopeId, part).list()
            .associate { Key(it.itemId, it.field) to Value(it.stageValue, it.replaced, it.revision) }

    /** Drop everything [rekeyId] staged for [scopeId] — the commit took it. */
    fun clear(rekeyId: String, scopeId: String) {
        ScopeRekeyStageEntity.delete("rekeyId = ?1 and scopeId = ?2", rekeyId, scopeId)
    }

    companion object {
        /** How long a stage no commit took is kept: a rotator that stops halfway rebuilds with a new key and a new id. */
        val TTL: Duration = Duration.ofDays(1)
        private const val CHUNK = 500
        const val CORE = "core"
    }
}

/** The staged scope-level values of an erased scope. Phase 660, beside the node stage's 650: no foreign key either way, and
 *  a phase of its own, so the order never rests on a tie. */
@ApplicationScoped
class ScopeRekeyStageScopeEraser : ScopeEraser {
    override val phase = 660

    override fun eraseScope(scopeId: String) {
        ScopeRekeyStageEntity.delete("scopeId", scopeId)
    }
}
