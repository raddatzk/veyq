package com.veyq.app.features.account.login

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * An outstanding login challenge (single-use, time-bounded), keyed by its opaque nonce. An email may hold
 * several at once, and a verify consumes only the one it names: keyed by email, anyone who knew the address
 * could replace or burn the challenge a device was still signing (uc.account.login E4). There is deliberately
 * no cap per email — evicting the oldest or refusing the newest is the same displacement lever again.
 */
@Entity
@Table(name = "veyq_login_challenge")
class LoginChallengeEntity : PanacheEntityBase {
    @Id
    @Column(name = "nonce", length = 64)
    lateinit var nonce: String

    @Column(name = "email", nullable = false, length = 254)
    lateinit var email: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<LoginChallengeEntity, String>
}

/** A session family = one login / device. */
@Entity
@Table(name = "veyq_session_family")
class SessionFamilyEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "user_id", nullable = false, length = 36)
    lateinit var userId: String

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "last_used_at", nullable = false)
    lateinit var lastUsedAt: Instant

    companion object : PanacheCompanionBase<SessionFamilyEntity, String> {
        const val ACTIVE = "ACTIVE"
        const val REVOKED = "REVOKED"
    }
}

/** A rotating refresh token (one chain per family). Only the SHA-256 hash is stored. */
@Entity
@Table(name = "veyq_refresh_token")
class RefreshTokenEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "family_id", nullable = false, length = 36)
    lateinit var familyId: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "generation", nullable = false)
    var generation: Int = 0

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<RefreshTokenEntity, String> {
        const val CURRENT = "CURRENT"
        const val SUPERSEDED = "SUPERSEDED"
    }
}

/** A short-lived access token. Only the SHA-256 hash is stored. */
@Entity
@Table(name = "veyq_access_token")
class AccessTokenEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "family_id", nullable = false, length = 36)
    lateinit var familyId: String

    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<AccessTokenEntity, String>
}
