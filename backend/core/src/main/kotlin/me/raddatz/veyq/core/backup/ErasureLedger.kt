package me.raddatz.veyq.core.backup

import me.raddatz.veyq.core.blobstore.ReclaimedKeys
import me.raddatz.veyq.core.moderation.ModerationEventEntity
import me.raddatz.veyq.core.moderation.block.ModerationBlockEntity
import me.raddatz.veyq.core.moderation.report.ModerationCaseEntity
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The erasure ledger's kinds (ADR 0056 decision 8) — a **sealed set**: [ErasureReapplication] re-applies a line through a
 * `when` over it, so a kind added here without its re-applier does not compile. An application's kinds are its own (a
 * Memories medium is `MEDIA`), written through [ErasureLedger.erasedByApplication] and re-applied through its
 * [ApplicationErasures], whose own `when` holds the same property for them.
 *
 * - [ACCOUNT], [TENANT], [SCOPE], [NODE], [SHARE] — a hard erasure no restored row would trigger again by itself: a
 *   restore brings the row back live, or with no trigger left (an account's request, a link's delete).
 * - [STORAGE_KEY] — a store object the reclaim sweep deleted: it erases nothing by itself; it tells a restore's object
 *   check that the key was erased, not lost, and the start counts a row that still names it.
 * - [BLOCK], [LIFT], [CASE] — moderation survives a restore (the owner, 2026-10-18; TCO Art. 3, DSA Art. 17): per item
 *   the last block or lift wins, and a case the database lacks comes back as a stub.
 */
enum class ErasureKind { ACCOUNT, TENANT, SCOPE, NODE, SHARE, STORAGE_KEY, BLOCK, LIFT, CASE }

/** On what a block stands: a moderator's or the Server Owner's measure, or an authority's order (ADR 0093 §§ 6, 12). */
enum class BlockBasis { MEASURE, ORDER }

/**
 * One line of the ledger: the JSON object `erasures.log` holds per line — `at`, `kind`, `id` and, by kind, the scope,
 * the block's item kind and target, the case's fields. Ids, storage keys and times only: never content, never a name,
 * never an address (operator-blind; the ledger outlives what it names on purpose, as an opaque id does in a hold).
 */
data class LedgerLine(val fields: Map<String, String?>) {
    val at: Instant get() = Instant.parse(fields.getValue("at"))
    val kind: String get() = fields.getValue("kind")!!
    val id: String get() = fields.getValue("id")!!
    operator fun get(name: String): String? = fields[name]

    fun json(): String = MAPPER.writeValueAsString(fields)

    companion object {
        private val MAPPER = ObjectMapper()

        /** A line of the file, or null when it is not one this reads (a torn last line, a hand edit). */
        fun parse(text: String): LedgerLine? = runCatching {
            @Suppress("UNCHECKED_CAST")
            val raw = MAPPER.readValue(text, Map::class.java) as Map<String, Any?>
            val fields = raw.mapValues { it.value?.toString() }
            LedgerLine(fields).takeIf { fields["at"] != null && fields["kind"] != null && fields["id"] != null }
                ?.also { it.at }
        }.getOrNull()
    }
}

/**
 * The outbox (`veyq_erasure_outbox`): a line written in the erasure's own transaction, so it exists exactly when the
 * erasure committed; [ErasureLedgerFile.sweep] appends it to the file and stamps [sentAt].
 */
@Entity
@Table(name = "veyq_erasure_outbox")
@DynamicUpdate
class ErasureOutboxEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "kind", nullable = false, length = 16)
    lateinit var kind: String

    @Column(name = "subject_id", nullable = false, length = 64)
    lateinit var subjectId: String

    @Column(name = "line", nullable = false, length = 1024)
    lateinit var line: String

    @Column(name = "recorded_at", nullable = false)
    lateinit var recordedAt: Instant

    @Column(name = "sent_at")
    var sentAt: Instant? = null

    companion object : PanacheCompanionBase<ErasureOutboxEntity, String>
}

/**
 * The application's half of the ledger (ADR 0056 decision 8, ADR 0079: an application may use core, never the reverse):
 * the kinds it writes through [ErasureLedger.erasedByApplication] and their re-applier, which core calls at start for a
 * line of one of [kinds]. Collected with `@All`, as a [me.raddatz.veyq.core.scope.ScopeEraser] is.
 */
interface ApplicationErasures {
    /** The ledger kinds this application writes; none is a name of [ErasureKind]. */
    val kinds: Set<String>

    /** Erase [id] (of [kind], in [scopeId]) if its row exists — in the caller's transaction; true when it erased. */
    fun reapply(kind: String, id: String, scopeId: String?): Boolean
}

/**
 * Writes the ledger's lines (ADR 0056 decision 8) — into the outbox, **in the caller's transaction** (`MANDATORY`): the
 * erasure and its line commit together or not at all. Every hard erasure no restored row would trigger again calls one
 * of these at the place it erases ([ErasureKind] lists them); a moderation block, its lift and a case's change too.
 */
@ApplicationScoped
class ErasureLedger : ReclaimedKeys {

    /** The store reclaim settled [storageKey]'s row: its objects are gone ([ErasureKind.STORAGE_KEY]). */
    @Transactional(Transactional.TxType.MANDATORY)
    override fun reclaimed(storageKey: String) = erased(ErasureKind.STORAGE_KEY, storageKey)

    /** A hard erasure of core's [kind] (never a moderation kind) of [id], in [scopeId] where it has one. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun erased(kind: ErasureKind, id: String, scopeId: String? = null) {
        require(kind != ErasureKind.BLOCK && kind != ErasureKind.LIFT && kind != ErasureKind.CASE) {
            "a moderation line is written by blockTaken, blockLifted or caseChanged"
        }
        write(kind.name, id, linkedMapOf("scope" to scopeId))
    }

    /** A hard erasure of an application's [kind] — one its [ApplicationErasures] re-applies. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun erasedByApplication(kind: String, id: String, scopeId: String?) {
        require(ErasureKind.entries.none { it.name == kind }) { "$kind is core's kind, not an application's" }
        write(kind, id, linkedMapOf("scope" to scopeId))
    }

    /** A block was taken: its item kind and target, its link and space, its case, who took it, on what [basis]. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun blockTaken(row: ModerationBlockEntity, basis: BlockBasis) {
        write(
            ErasureKind.BLOCK.name, row.id,
            linkedMapOf(
                "item" to row.kind, "target" to row.targetId, "share" to row.shareId, "scope" to row.scopeId,
                "case" to row.caseId, "by" to row.takenBy, "basis" to basis.name,
            ),
            at = row.takenAt,
        )
    }

    /** A block was lifted — by a person, a decided objection, or the erasure of what it stood on. */
    @Transactional(Transactional.TxType.MANDATORY)
    fun blockLifted(row: ModerationBlockEntity) {
        write(ErasureKind.LIFT.name, row.id, linkedMapOf("item" to row.kind, "target" to row.targetId), at = row.liftedAt)
    }

    /**
     * A case changed (opened, decided, closed, reopened, an event appended): its stub as it stands — item, space,
     * category, state, when it opened, and its retention date, the latest `expires_at` of its events once every one has
     * one (the case is closed), else none. The last line of a case wins.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    fun caseChanged(caseId: String) {
        val case = ModerationCaseEntity.findById(caseId) ?: return
        val events = ModerationEventEntity.list("caseId", caseId)
        val opened = events.minOfOrNull { it.occurredAt }
        val retainUntil = if (events.isNotEmpty() && events.all { it.expiresAt != null }) events.maxOf { it.expiresAt!! } else null
        write(
            ErasureKind.CASE.name, case.id,
            linkedMapOf(
                "item" to case.itemKind, "target" to case.itemId, "scope" to case.scopeId, "category" to case.category,
                "state" to case.state, "openedAt" to opened?.toString(), "retainUntil" to retainUntil?.toString(),
            ),
        )
    }

    private fun write(kind: String, subject: String, extra: LinkedHashMap<String, String?>, at: Instant? = null) {
        val recorded = nextRecordedAt()
        val fields = linkedMapOf<String, String?>("at" to (at ?: recorded).toString(), "kind" to kind, "id" to subject)
        extra.forEach { (k, v) -> if (v != null) fields[k] = v }
        ErasureOutboxEntity().apply {
            id = UUID.randomUUID().toString()
            this.kind = kind
            subjectId = subject
            line = LedgerLine(fields).json()
            recordedAt = recorded
        }.persist()
    }

    /**
     * The outbox's order is the file's, and the file's order is what "the last line wins" reads (a block and its lift, a
     * case's events): so every row gets a `recorded_at` strictly after the one before it, on the column's millisecond.
     * A plain `Instant.now()` gave a case's last event and its close, written in one transaction, the same millisecond,
     * and the sweep's tie-break — a random id — then put the case's open state last about half the time.
     */
    private fun nextRecordedAt(): Instant {
        val now = Instant.now().toEpochMilli()
        return Instant.ofEpochMilli(lastMillis.updateAndGet { last -> maxOf(now, last + 1) })
    }

    private val lastMillis = AtomicLong(0)
}
