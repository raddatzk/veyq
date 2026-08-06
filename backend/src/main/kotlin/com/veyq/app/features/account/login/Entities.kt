package com.veyq.app.features.account.login

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** One outstanding login challenge per email (single-use, time-bounded). The nonce is opaque. */
@Entity
@Table(name = "veyq_login_challenge")
class LoginChallengeEntity : PanacheEntityBase {
    @Id
    @Column(name = "email", length = 254)
    lateinit var email: String

    @Column(name = "nonce", nullable = false)
    lateinit var nonce: String

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
