package me.raddatz.veyq.core.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/** A global user account. The server stores only public keys, wrapped (ciphertext) key material and
 *  low-sensitivity identity metadata — never the password, never content keys. */
@Entity
@Table(name = "veyq_user")
@DynamicUpdate
class VeyqUser : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "email", nullable = false, length = 254)
    lateinit var email: String

    // No display name on the account — identity is a per-tenant `username` + sealed real name on
    // veyq_tenant_profile (ADR 0023). The account carries only email + keys + server role.

    @Column(name = "server_role", nullable = false, length = 32)
    lateinit var serverRole: String

    @Column(name = "suite_id", nullable = false, length = 16)
    lateinit var suiteId: String

    @Column(name = "identity_public_key", nullable = false)
    lateinit var identityPublicKey: String

    @Column(name = "wrapped_identity_private_key", nullable = false)
    lateinit var wrappedIdentityPrivateKey: String

    @Column(name = "auth_public_key", nullable = false)
    lateinit var authPublicKey: String

    /** The account's Secret-Key proof key (ADR 0060 Decision 2): an Ed25519 PUBLIC key the client derives from the
     *  Secret Key alone, registered at birth and replaced only when the account is secured (cr-secure-the-account
     *  decision 7: the publish registers the new one under a signature of this one, and the key chain keeps both).
     *  Change-password, recovery and change-email leave it as it is. Each of those flows must carry a signature under
     *  it (`SecretKeyProof`). Null only for an account born before it existed, and such an account can do none of them. */
    @Column(name = "secret_key_proof_public_key")
    var secretKeyProofPublicKey: String? = null

    /** The account-global "ask before adding me to groups" preference (uc.spaces.consent-group-add, cr-0094,
     *  ADR 0014 §5) — default false. When true, a GroupOwner's add becomes a consent proposal (a soft
     *  `pendingConsent` membership) instead of a direct-add. Low-sensitivity server-visible operational
     *  metadata — never a key or content. */
    @Column(name = "require_group_add_consent", nullable = false)
    var requireGroupAddConsent: Boolean = false

    /** Whether the account holder dismissed veyq's donation banner (uc.platform.support-the-project, ADR 0124) —
     *  default false, account-global like [requireGroupAddConsent], so a dismissal holds in every tenant and on every
     *  device. Low-sensitivity server-visible operational metadata, never content; served only to the holder. */
    @Column(name = "donation_banner_dismissed", nullable = false)
    var donationBannerDismissed: Boolean = false

    /** When the account holder last showed they read mail sent to [email] (ADR 0074 Decision 1) — a fact about the
     *  address, not a permission: nothing is gated on it (Decision 3). Null until one of the four events in
     *  [confirmAddress]'s callers. Server-visible operational metadata, like the address itself.
     *
     *  Only its presence is read today ([me.raddatz.veyq.core.account.email.EmailConfirmationHandler] surfaces
     *  "confirmed"); the time is kept because ADR 0074 Decision 1 decides the record is *when* the holder showed
     *  control, not merely *that* they did (inbox #318 kept it on that ground — narrowing it to a flag is an ADR
     *  change, not a cleanup). The account's birth time is not stored at all: nothing read it (#318). */
    @Column(name = "email_confirmed_at")
    var emailConfirmedAt: Instant? = null

    /**
     * Record address control, **iff** [address] — the address the proof was mailed to — is still this account's
     * [email] (ADR 0074 Decision 1: the record is bound to the address it confirmed). Returns whether it stamped.
     *
     * The one writer of [emailConfirmedAt], called in the same transaction as each of the events that prove it
     * (Decision 2): a redeemed MEMBER invite, a completed reset, a confirmation link, and a confirmed change of the
     * address (Decision 8: `EmailChangeHandler` sets the new [email] and stamps it in one write — the link proved
     * it). Any other path that changes [email] must clear the stamp in the same write.
     */
    fun confirmAddress(address: String, at: Instant = Instant.now()): Boolean {
        if (normalizeEmail(address) != email) return false
        emailConfirmedAt = at
        return true
    }

    companion object : PanacheCompanionBase<VeyqUser, String>
}
