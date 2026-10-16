package me.raddatz.veyq.core.admin.instance

import me.raddatz.veyq.core.crypto.SealedValueFingerprints
import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * The instance's own SMTP settings (ADR 0097 decision 3): one row once the Server Owner saved them. The password is
 * sealed under the config-secret KEK with the fingerprint of the key that sealed it (ADR 0050, ADR 0089 decision 4).
 * The `code*` columns are the outstanding confirmation code, hashed; `confirmed*` what a code confirmed (decision 5).
 */
@Entity
@Table(name = "veyq_instance_mail_settings")
@DynamicUpdate
class InstanceMailSettingsEntity : PanacheEntityBase {
    @Id
    @Column(name = "id")
    var id: Int = SINGLETON_ID

    @Column(name = "host", length = 253)
    var host: String? = null

    @Column(name = "port")
    var port: Int? = null

    @Column(name = "from_address", length = 254)
    var fromAddress: String? = null

    @Column(name = "username", length = 254)
    var username: String? = null

    @Column(name = "password_ciphertext")
    var passwordCiphertext: String? = null

    @Column(name = "password_nonce")
    var passwordNonce: String? = null

    @Column(name = "password_fingerprint", length = 64)
    var passwordFingerprint: String? = null

    @Column(name = "tls", nullable = false)
    var tls: Boolean = true

    /** The Server Owner who saved the settings: the one account instance mail reaches. */
    @Column(name = "owner_user_id", columnDefinition = "uuid")
    var ownerUserId: String? = null

    @Column(name = "code_hash", length = 64)
    var codeHash: String? = null

    @Column(name = "code_address", length = 254)
    var codeAddress: String? = null

    @Column(name = "code_expires_at")
    var codeExpiresAt: Instant? = null

    @Column(name = "code_attempts", nullable = false)
    var codeAttempts: Int = 0

    @Column(name = "test_mail_failed", nullable = false)
    var testMailFailed: Boolean = false

    @Column(name = "confirmed_address", length = 254)
    var confirmedAddress: String? = null

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null

    companion object : PanacheCompanionBase<InstanceMailSettingsEntity, Int> {
        const val SINGLETON_ID = 1
    }
}

/** The instance SMTP transport and its sealed password, as the mailer and the handler read it. */
data class InstanceMailTransport(
    val host: String?,
    val port: Int?,
    val from: String?,
    val username: String?,
    val passwordCiphertext: String?,
    val passwordNonce: String?,
    val passwordFingerprint: String?,
    val tls: Boolean,
) {
    val passwordSet: Boolean get() = passwordCiphertext != null
}

/** The whole row: the transport, whose it is, and where the confirmation stands. */
data class InstanceMailRow(
    val transport: InstanceMailTransport,
    val ownerUserId: String?,
    val codeHash: String?,
    val codeAddress: String?,
    val codeExpiresAt: Instant?,
    val codeAttempts: Int,
    val testMailFailed: Boolean,
    val confirmedAddress: String?,
    val confirmedAt: Instant?,
)

/** Persistence for the instance mail settings. Each write is its own transaction; a send never runs inside one. */
@ApplicationScoped
class InstanceMailSettingsStore {

    fun find(): InstanceMailRow? =
        QuarkusTransaction.joiningExisting().call { InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID)?.toRow() }

    /**
     * Replace the transport and start the confirmation over (ADR 0097 decision 5: every save asks again): the confirmed
     * address and any outstanding code are cleared, and [codeHash] — when a code is minted for this save — is stored
     * for [codeAddress] until [codeExpiresAt].
     */
    fun save(
        transport: InstanceMailTransport,
        ownerUserId: String,
        codeHash: String?,
        codeAddress: String?,
        codeExpiresAt: Instant?,
    ) {
        QuarkusTransaction.requiringNew().run {
            val e = InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID)
                ?: InstanceMailSettingsEntity().also { it.persist() }
            e.host = transport.host
            e.port = transport.port
            e.fromAddress = transport.from
            e.username = transport.username
            e.passwordCiphertext = transport.passwordCiphertext
            e.passwordNonce = transport.passwordNonce
            e.passwordFingerprint = transport.passwordFingerprint
            e.tls = transport.tls
            e.ownerUserId = ownerUserId
            e.codeHash = codeHash
            e.codeAddress = codeAddress
            e.codeExpiresAt = codeExpiresAt
            e.codeAttempts = 0
            e.testMailFailed = false
            e.confirmedAddress = null
            e.confirmedAt = null
        }
    }

    /**
     * Record that the test mail of the save that minted [codeHash] could not be delivered, and drop that code — it
     * reached nobody — only if no later save replaced it.
     */
    fun markTestMailFailed(codeHash: String) {
        QuarkusTransaction.requiringNew().run {
            InstanceMailSettingsEntity.update(
                "testMailFailed = true, codeHash = null, codeAddress = null, codeExpiresAt = null where id = ?1 and codeHash = ?2",
                InstanceMailSettingsEntity.SINGLETON_ID, codeHash,
            )
        }
    }

    /**
     * Enter a code, under the row's lock: [verify] decides, from the row, whether this entry confirms. A confirming
     * entry records [confirmedAddress] at [at] and spends the code; a wrong one counts an attempt and spends the code
     * at [maxAttempts]. Returns whether it confirmed.
     */
    fun enterCode(at: Instant, maxAttempts: Int, verify: (InstanceMailRow) -> String?): Boolean =
        QuarkusTransaction.requiringNew().call {
            val e = InstanceMailSettingsEntity.findById(InstanceMailSettingsEntity.SINGLETON_ID, LockModeType.PESSIMISTIC_WRITE)
                ?: return@call false
            val confirmed = verify(e.toRow())
            if (confirmed != null) {
                e.confirmedAddress = confirmed
                e.confirmedAt = at
                e.codeHash = null
                e.codeAddress = null
                e.codeExpiresAt = null
                e.codeAttempts = 0
                true
            } else {
                if (e.codeHash != null) {
                    e.codeAttempts += 1
                    if (e.codeAttempts >= maxAttempts) {
                        e.codeHash = null
                        e.codeAddress = null
                        e.codeExpiresAt = null
                    }
                }
                false
            }
        }

    private fun InstanceMailSettingsEntity.toRow() = InstanceMailRow(
        transport = InstanceMailTransport(host, port, fromAddress, username, passwordCiphertext, passwordNonce, passwordFingerprint, tls),
        ownerUserId = ownerUserId,
        codeHash = codeHash,
        codeAddress = codeAddress,
        codeExpiresAt = codeExpiresAt,
        codeAttempts = codeAttempts,
        testMailFailed = testMailFailed,
        confirmedAddress = confirmedAddress,
        confirmedAt = confirmedAt,
    )
}

/**
 * The instance mail password's part of the instance-wide config-secret state (ADR 0089 decision 4) — the sealed column
 * this table adds, contributed as every holder of one does (`ConfigSecretKeyGuard`). A ciphertext with no fingerprint
 * names no key at all, so it counts as foreign.
 */
@ApplicationScoped
class InstanceMailSealedFingerprints : SealedValueFingerprints {
    override fun sealedFingerprints(): List<String> =
        InstanceMailSettingsEntity.list("passwordCiphertext is not null").map { it.passwordFingerprint ?: "" }
}
