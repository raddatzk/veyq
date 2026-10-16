package me.raddatz.veyq.core.admin.instance

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PersistenceException
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The release check's setting (ADR 0097 decision 1): one row, seeded on and with the GitHub source by its migration. */
@Entity
@Table(name = "veyq_release_check")
@DynamicUpdate
class ReleaseCheckEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = SINGLETON_ID

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    /** A mirror's base URL; null = the published GitHub Releases. */
    @Column(name = "source", length = 2048)
    var source: String? = null

    @Column(name = "last_success_at")
    var lastSuccessAt: Instant? = null

    companion object : PanacheCompanionBase<ReleaseCheckEntity, Int> {
        const val SINGLETON_ID = 1
    }
}

/** One notice the check found (ADR 0097 decision 2). [noticeKey] is [FoundNotice.key], unique: found once, kept once. */
@Entity
@Table(name = "veyq_release_notice")
@DynamicUpdate
class ReleaseNoticeEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "notice_key", nullable = false, length = 96)
    lateinit var noticeKey: String

    @Column(name = "kind", nullable = false, length = 32)
    lateinit var kind: String

    @Column(name = "release_version", nullable = false, length = 32)
    lateinit var releaseVersion: String

    @Column(name = "notice_date")
    var noticeDate: LocalDate? = null

    @Column(name = "days_ahead")
    var daysAhead: Int? = null

    @Column(name = "found_at", nullable = false)
    lateinit var foundAt: Instant

    @Column(name = "mailed_at")
    var mailedAt: Instant? = null

    companion object : PanacheCompanionBase<ReleaseNoticeEntity, String>
}

data class ReleaseCheckSetting(val enabled: Boolean, val source: String?, val lastSuccessAt: Instant?)

/** A stored notice as the console and the mail read it. */
data class StoredNotice(
    val id: String,
    val kind: NoticeKind,
    val release: ReleaseVersion,
    val date: LocalDate?,
    val daysAhead: Int?,
    val foundAt: Instant,
    val mailedAt: Instant?,
)

/**
 * Persistence for the release check: its setting and what it found. Each write is its own transaction — the check
 * runs on the scheduler's thread, outside any request, and holds no transaction across a request to the source.
 */
@ApplicationScoped
class ReleaseNoticeStore {

    fun setting(): ReleaseCheckSetting =
        QuarkusTransaction.joiningExisting().call {
            (ReleaseCheckEntity.findById(ReleaseCheckEntity.SINGLETON_ID))
                ?.let { ReleaseCheckSetting(it.enabled, it.source, it.lastSuccessAt) }
                ?: ReleaseCheckSetting(enabled = true, source = null, lastSuccessAt = null)
        }

    fun saveSetting(enabled: Boolean, source: String?) {
        QuarkusTransaction.requiringNew().run {
            val row = ReleaseCheckEntity.findById(ReleaseCheckEntity.SINGLETON_ID)
                ?: ReleaseCheckEntity().also { it.persist() }
            row.enabled = enabled
            row.source = source
        }
    }

    fun recordSuccess(at: Instant) {
        QuarkusTransaction.requiringNew().run {
            val row = ReleaseCheckEntity.findById(ReleaseCheckEntity.SINGLETON_ID)
                ?: ReleaseCheckEntity().also { it.persist() }
            row.lastSuccessAt = at
        }
    }

    /**
     * Store every notice of [found] not stored yet, and return how many were new. A notice already stored — by key —
     * is left exactly as it is, its `found_at` and `mailed_at` included; that is what makes a notice mailed once. Each
     * insert is its own transaction, so a key a concurrent check wrote first (the unique index refuses it) costs only
     * that row.
     */
    fun recordFound(found: List<FoundNotice>, at: Instant): Int = found.count { notice ->
        try {
            QuarkusTransaction.requiringNew().call {
                if (ReleaseNoticeEntity.count("noticeKey", notice.key) > 0) false else {
                    ReleaseNoticeEntity().apply {
                        id = UUID.randomUUID().toString()
                        noticeKey = notice.key
                        kind = notice.kind.name
                        releaseVersion = notice.release.toString()
                        noticeDate = notice.date
                        daysAhead = notice.daysAhead
                        foundAt = at
                    }.persist()
                    true
                }
            }
        } catch (e: PersistenceException) {
            false
        }
    }

    /** Every stored notice. */
    fun notices(): List<StoredNotice> =
        QuarkusTransaction.joiningExisting().call { ReleaseNoticeEntity.listAll().mapNotNull { it.toStored() } }

    /**
     * Claim [id] for its one mail: set `mailed_at` to [at] if it has none, and say whether this call set it. A notice
     * already claimed — mailed, or being mailed — is never claimed again, which is what makes it mailed at most once.
     */
    fun claimForMail(id: String, at: Instant): Boolean =
        QuarkusTransaction.requiringNew().call {
            ReleaseNoticeEntity.update("mailedAt = ?1 where id = ?2 and mailedAt is null", at, id) == 1
        }

    /**
     * Give back the claim [claimForMail] took, because that send failed: the next check may try again. Only the mailer
     * claims, and only from the check, which never runs twice at once (`ConcurrentExecution.SKIP`), so the claim given
     * back is the one this run took. (Matched by id alone: a `TIMESTAMP(3)` column does not round-trip an `Instant`'s
     * nanoseconds, so an equality on the time would miss on MariaDB.)
     */
    fun releaseClaim(id: String) {
        QuarkusTransaction.requiringNew().run {
            ReleaseNoticeEntity.update("mailedAt = null where id = ?1", id)
        }
    }

    /** A row whose kind or release this build cannot read (a later build's) is left out rather than guessed at. */
    private fun ReleaseNoticeEntity.toStored(): StoredNotice? {
        val kind = NoticeKind.entries.firstOrNull { it.name == kind } ?: return null
        val release = ReleaseVersion.parse(releaseVersion) ?: return null
        return StoredNotice(id, kind, release, noticeDate, daysAhead, foundAt, mailedAt)
    }
}
