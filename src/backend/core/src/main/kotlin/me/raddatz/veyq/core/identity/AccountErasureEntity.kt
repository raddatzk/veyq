package me.raddatz.veyq.core.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * A pending account erasure (ADR 0101 decisions 1-3, uc.account.erase-account): the account stopped at [requestedAt]
 * and `AccountPurge` erases it at [dueAt] — the request plus `veyq.account.erasure.window`, fixed when it was requested
 * (a later change of the window moves no stored date) — unless the Server Owner stops it first, which deletes this
 * row. While it exists, sign-in refuses the account as it refuses a wrong password, and a password reset mints nothing. [mailedAt] is when the instance mail telling the address went out; the purge's hourly
 * tick retries it until then.
 *
 * In the kernel, beside the account row, because the sign-in and the reset ask it: neither may import the erasure slice.
 */
@Entity
@Table(name = "veyq_account_erasure")
@DynamicUpdate
class AccountErasureEntity : PanacheEntityBase {
    @Id
    @Column(name = "account_id", columnDefinition = "uuid")
    lateinit var accountId: String

    @Column(name = "requested_at", nullable = false)
    lateinit var requestedAt: Instant

    @Column(name = "requested_by", nullable = false, length = 16)
    lateinit var requestedBy: String

    @Column(name = "due_at", nullable = false)
    lateinit var dueAt: Instant

    @Column(name = "mailed_at")
    var mailedAt: Instant? = null

    companion object : PanacheCompanionBase<AccountErasureEntity, String> {
        const val SELF = "SELF"
        const val SERVER_OWNER = "SERVER_OWNER"

        /** Whether [accountId]'s erasure is pending — the account then signs in nowhere. */
        fun isPending(accountId: String): Boolean = count("accountId", accountId) > 0
    }
}
