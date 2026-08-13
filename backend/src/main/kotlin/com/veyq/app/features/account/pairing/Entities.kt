package com.veyq.app.features.account.pairing

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One device-pairing channel (ADR 0059 §8) — the relay two devices run a single linking ceremony over.
 *
 * `AccountRecoveryEntity`'s shape, field for field, except where the ceremony forces a difference: only the
 * SHA-256 handle hash is stored (never the handle), liveness is checked at read *and* swept, and the row is
 * single-use. What it holds beyond that are three values the server relays and interprets none of — the routing
 * hash, one [answer] and one [envelope].
 *
 * [userId] is **nullable**, and that is the one deliberate departure. In the *sign in with a QR code* direction
 * the channel is opened by a device with no session, so the account is unavailable at open — *"which account
 * this becomes is decided by whoever scans"* (ADR 0059 §3), written as a column. It is bound at delegation, and
 * that is also where §8's one-outstanding-pairing-per-account is enforced.
 *
 * [delegatedFamilyId] is what lets an abandoned ceremony take its session family with it: a channel that reaches
 * a terminal state without being collected has its family revoked by the sweep, so no listed family is left
 * holding a live 30-day refresh token nobody picked up.
 */
@Entity
@Table(name = "veyq_device_pairing")
class DevicePairingEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "handle_hash", nullable = false, length = 64)
    lateinit var handleHash: String

    /** The account this channel became — null until the trusted device delegates (ADR 0059 §3). */
    @Column(name = "user_id", length = 36)
    var userId: String? = null

    /** The family minted for this ceremony; revoked with the channel if it dies uncollected. */
    @Column(name = "delegated_family_id", length = 36)
    var delegatedFamilyId: String? = null

    /** The answering device's ephemeral transport key, or its commitment to the transcribed code. The server
     *  cannot tell which, and §6 is why it must not be able to. */
    @Column(name = "answer", columnDefinition = "TEXT")
    var answer: String? = null

    /** The sealed grant (ADR 0059 §3). Ciphertext to the server: it holds it, hands it over once, cannot open it. */
    @Column(name = "envelope", columnDefinition = "TEXT")
    var envelope: String? = null

    @Column(name = "status", nullable = false, length = 16)
    lateinit var status: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<DevicePairingEntity, String> {
        /** Open, awaiting the answering device. */
        const val PENDING = "PENDING"

        /** Answered; awaiting the trusted device's delegation and seal. */
        const val ANSWERED = "ANSWERED"

        /** Sealed; awaiting collection by the device being onboarded. */
        const val SEALED = "SEALED"

        /** The grant was handed over. Single-use: a second collect reads as dead. */
        const val COLLECTED = "COLLECTED"

        /** Replaced by a newer ceremony on the same account (§8). */
        const val SUPERSEDED = "SUPERSEDED"

        /** Past its TTL, flipped by the scheduled sweep. */
        const val EXPIRED = "EXPIRED"

        /**
         * There is deliberately no `ABANDONED` and no abandon endpoint. §6's three-attempt ceiling is counted on
         * the trusted device, which never tells the server — an abandoned ceremony simply expires, which is
         * already what uc.account.onboard-device E3 describes for an uncollected grant.
         */
        val TERMINAL = listOf(COLLECTED, SUPERSEDED, EXPIRED)

        /** Terminal states a channel can reach *without* the grant ever being picked up — the ones whose
         *  delegated family must be revoked rather than handed to a device that never arrived. */
        val UNSPENT_TERMINAL = listOf(SUPERSEDED, EXPIRED)
    }
}
