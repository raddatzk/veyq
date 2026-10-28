package me.raddatz.veyq.core.identity

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate

/**
 * A member's identity **within one tenant** (cr-0083, ADR 0023): a per-`(user, tenant)` row — one per person
 * per tenant, independent of how many scopes they hold there. `username` is a plaintext handle, unique within
 * the tenant (`UNIQUE(tenant_id, username)`); `sealedName` is the first+last name sealed under `K_tenant`
 * (ciphertext, operator-blind) — **nullable**: a member may leave it out.
 * `sealedAvatar` is the profile picture (ADR 0084): one constant-length ciphertext under `K_tenant`, null = none;
 * `avatarRevision` is bumped on every set and clear and is what the directory and the avatar read's `ETag` carry.
 * `sealedJoinRecord` is the member's join record (ADR 0111 decisions 3 and 12), sealed under `K_tenant`: written once
 * at the invite's accept, never by the profile op, null for the tenant's first owner.
 */
@Entity
@Table(name = "veyq_tenant_profile")
@DynamicUpdate
class TenantProfileEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "username", nullable = false, length = 32)
    lateinit var username: String

    @Column(name = "sealed_name")
    var sealedName: String? = null

    @Column(name = "sealed_avatar")
    var sealedAvatar: String? = null

    @Column(name = "avatar_revision", nullable = false)
    var avatarRevision: Int = 0

    @Column(name = "sealed_join_record")
    var sealedJoinRecord: String? = null

    /** Who rooted this join (cr-trust-anchors-survive-rotation decision 11): the signer of the invite it was accepted
     *  through, written with [sealedJoinRecord]; null for the tenant's first owner. */
    @Column(name = "join_signer_user_id", columnDefinition = "uuid")
    var joinSignerUserId: String? = null

    /** That owner's endorsement of [sealedJoinRecord] (decision 12), written by their securing; null until then, for a
     *  record it declared, and whenever a new record replaces the one it endorsed. */
    @Column(name = "join_endorsement")
    var joinEndorsement: String? = null

    /** The revision the wire shows: `0` = no picture, else the counter (which a clear also bumped, ADR 0084). */
    fun wireAvatarRevision(): Int = if (sealedAvatar == null) 0 else avatarRevision

    companion object : PanacheCompanionBase<TenantProfileEntity, String>
}
