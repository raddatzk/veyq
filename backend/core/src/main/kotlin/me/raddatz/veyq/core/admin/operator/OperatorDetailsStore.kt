package me.raddatz.veyq.core.admin.operator

import me.raddatz.veyq.core.instance.VeyqInstance
import me.raddatz.veyq.core.terms.RecipientKind
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * The instance's operating mode and the values a host publishes about itself (ADR 0093 §§ 11 and 14, ADR 0035
 * decisions 6–7): one row once the Server Owner chose a mode or saved a value. `operating_mode` NULL reads as
 * [OperatingMode.ORGANISATION] — "until one is chosen". The `code*` columns are the notification address's outstanding
 * confirmation code, hashed; `confirmed*` what a code confirmed (ADR 0097 decision 5, [me.raddatz.veyq.core.admin.instance.CodeConfirmation]).
 */
@Entity
@Table(name = "veyq_operator_details")
@DynamicUpdate
class OperatorDetailsEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = SINGLETON_ID

    @Column(name = "operating_mode", length = 16)
    var operatingMode: String? = null

    @Column(name = "contact_email", length = 254)
    var contactEmail: String? = null

    /** The contact's language tags, comma-joined in the order given; NULL = none named. */
    @Column(name = "contact_languages", length = 512)
    var contactLanguages: String? = null

    @Column(name = "statement_email", length = 254)
    var statementEmail: String? = null

    @Column(name = "notification_email", length = 254)
    var notificationEmail: String? = null

    @Column(name = "provider_name", length = 200)
    var providerName: String? = null

    @Column(name = "provider_address", length = 500)
    var providerAddress: String? = null

    @Column(name = "provider_email", length = 254)
    var providerEmail: String? = null

    @Column(name = "provider_representative", length = 200)
    var providerRepresentative: String? = null

    @Column(name = "supervisory_authority", length = 500)
    var supervisoryAuthority: String? = null

    @Column(name = "data_protection_officer", length = 500)
    var dataProtectionOfficer: String? = null

    @Column(name = "code_hash", length = 64)
    var codeHash: String? = null

    @Column(name = "code_address", length = 254)
    var codeAddress: String? = null

    @Column(name = "code_expires_at")
    var codeExpiresAt: Instant? = null

    @Column(name = "code_attempts", nullable = false)
    var codeAttempts: Int = 0

    @Column(name = "code_failed", nullable = false)
    var codeFailed: Boolean = false

    @Column(name = "confirmed_address", length = 254)
    var confirmedAddress: String? = null

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null

    companion object : PanacheCompanionBase<OperatorDetailsEntity, Int> {
        const val SINGLETON_ID = 1
    }
}

/** How the instance is run, on the Server Owner's own assessment (ADR 0093 § 14). The wire enum's two values. */
enum class OperatingMode { PRIVATE, ORGANISATION }

/** The values the Server Owner sets, each null (or empty) where none is set. */
data class OperatorValues(
    val contactEmail: String? = null,
    val contactLanguages: List<String> = emptyList(),
    val statementEmail: String? = null,
    val notificationEmail: String? = null,
    val providerName: String? = null,
    val providerAddress: String? = null,
    val providerEmail: String? = null,
    val providerRepresentative: String? = null,
    val supervisoryAuthority: String? = null,
    /** The privacy notice's data-protection officer (ADR 0035 decision 8), where one is appointed. */
    val dataProtectionOfficer: String? = null,
    /** The recipients the privacy notice names, in the Server Owner's order. */
    val recipients: List<PublishedRecipient> = emptyList(),
)

/**
 * One recipient the privacy notice names (ADR 0035 decision 8): who, what they do as a [kind] the notice names in each
 * language, where — [country] an ISO 3166-1 alpha-2 code the notice names in each language too
 * (`core/terms/RecipientCountries`), null when not named. [description] is an [RecipientKind.OTHER] recipient's own
 * words, null for every other kind ([RecipientKind.accepts]).
 */
data class PublishedRecipient(val name: String, val kind: RecipientKind, val description: String?, val country: String?)

/**
 * The recipients, one row each, `list_index` their order (0-based) and their key: the list is replaced whole on every
 * save, never edited in place. The Server Owner's own published values, like the row above — no account's.
 */
@Entity
@Table(name = "veyq_operator_recipient")
@DynamicUpdate
class OperatorRecipientEntity : PanacheEntityBase {
    @Id
    @Column(name = "list_index")
    var position: Int = 0

    @Column(name = "name", nullable = false, length = 200)
    lateinit var name: String

    /** A [RecipientKind]'s name. */
    @Column(name = "kind", nullable = false, length = 20)
    lateinit var kind: String

    @Column(name = "description", length = 120)
    var description: String? = null

    /** An ISO 3166-1 alpha-2 code (`core/terms/RecipientCountries`), null when not named. */
    @Column(name = "country", length = 2)
    var country: String? = null

    companion object : PanacheCompanionBase<OperatorRecipientEntity, Int>
}

/** The whole row as the handler reads it; [mode] is null where none was ever chosen. */
data class OperatorRow(
    val mode: OperatingMode?,
    val values: OperatorValues,
    val codeHash: String?,
    val codeAddress: String?,
    val codeExpiresAt: Instant?,
    val codeAttempts: Int,
    val codeFailed: Boolean,
    val confirmedAddress: String?,
    val confirmedAt: Instant?,
)

/**
 * Persistence for the operator details. Every method runs in the caller's transaction.
 *
 * **A write takes the instance singleton's row lock first** ([VeyqInstance], which exists from first boot), then finds
 * or creates this row under it: two first writes would otherwise both read "no row" and the second fail on the key. The
 * capability switches' write takes the same lock (`CapabilitySwitchStore.set`), so a mode change that sets its preset
 * holds it once and re-enters it — one lock order, instance row then this row, for every writer here.
 */
@ApplicationScoped
class OperatorDetailsStore {

    fun find(): OperatorRow? = OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID)?.toRow(recipients())

    /** The recipients in their order. */
    fun recipients(): List<PublishedRecipient> =
        OperatorRecipientEntity.listAll().sortedBy { it.position }
            .map { PublishedRecipient(it.name, RecipientKind.valueOf(it.kind), it.description, it.country) }

    /** Replace the recipients with [list], in its order. Call under [lockedRow]. */
    fun replaceRecipients(list: List<PublishedRecipient>) {
        OperatorRecipientEntity.deleteAll()
        OperatorRecipientEntity.flush()
        list.forEachIndexed { i, r ->
            OperatorRecipientEntity().apply {
                position = i; name = r.name; kind = r.kind.name; description = r.description; country = r.country
            }.persist()
        }
    }

    /** The row, locked for writing, created empty where none exists yet. */
    fun lockedRow(): OperatorDetailsEntity {
        VeyqInstance.findById(VeyqInstance.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
        return OperatorDetailsEntity.findById(OperatorDetailsEntity.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
            ?: OperatorDetailsEntity().also { it.persist() }
    }

    /** Record that the code minted as [codeHash] could not be mailed, and drop it — unless a later save replaced it. */
    fun markCodeFailed(codeHash: String) {
        OperatorDetailsEntity.update(
            "codeFailed = true, codeHash = null, codeAddress = null, codeExpiresAt = null where id = ?1 and codeHash = ?2",
            OperatorDetailsEntity.SINGLETON_ID, codeHash,
        )
    }

    companion object {
        fun OperatorDetailsEntity.toRow(recipients: List<PublishedRecipient>) = OperatorRow(
            mode = operatingMode?.let { OperatingMode.valueOf(it) },
            values = OperatorValues(
                contactEmail = contactEmail,
                contactLanguages = contactLanguages?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
                statementEmail = statementEmail,
                notificationEmail = notificationEmail,
                providerName = providerName,
                providerAddress = providerAddress,
                providerEmail = providerEmail,
                providerRepresentative = providerRepresentative,
                supervisoryAuthority = supervisoryAuthority,
                dataProtectionOfficer = dataProtectionOfficer,
                recipients = recipients,
            ),
            codeHash = codeHash,
            codeAddress = codeAddress,
            codeExpiresAt = codeExpiresAt,
            codeAttempts = codeAttempts,
            codeFailed = codeFailed,
            confirmedAddress = confirmedAddress,
            confirmedAt = confirmedAt,
        )
    }
}
