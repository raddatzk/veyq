package com.veyq.app.shared.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** A global user account. The server stores only public keys, wrapped (ciphertext) key material and
 *  low-sensitivity identity metadata — never the password, never content keys. */
@Entity
@Table(name = "veyq_user")
class VeyqUser : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
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

    @Column(name = "recovery_master_key_tag")
    var recoveryMasterKeyTag: String? = null

    /** The account-global "ask before adding me to groups" preference (uc.spaces.consent-group-add, cr-0094,
     *  ADR 0014 §5) — default false. When true, a GroupOwner's add becomes a consent proposal (a soft
     *  `pendingConsent` membership) instead of a direct-add. Low-sensitivity server-visible operational
     *  metadata — never a key or content. */
    @Column(name = "require_group_add_consent", nullable = false)
    var requireGroupAddConsent: Boolean = false

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<VeyqUser, String>
}
