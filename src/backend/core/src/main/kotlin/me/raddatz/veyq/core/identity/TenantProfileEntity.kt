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
 * the tenant (`UNIQUE(tenant_id, username)`); `sealedName` is the first+last name (ciphertext, operator-blind) —
 * **nullable**: a member may leave it out. `sealedAvatar` is the profile picture (ADR 0084): one constant-length
 * ciphertext, null = none; `avatarRevision` is bumped on every set and clear — and on every rotation's re-wrap of the
 * picture's key, so a reader's cached pair is fetched again — and is what the directory and the avatar read's `ETag`
 * carry. `sealedJoinRecord` is the member's join record (ADR 0111 decisions 3 and 12): written once at the invite's
 * accept, never by the profile op, null for the tenant's first owner.
 *
 * **Each of the three is sealed under a value key of its own** (ADR 0115): `K_v`, minted by the member at every write,
 * and only `K_v` is under `K_tenant` — its wrap beside the value (`wrapped*ValueKey`, non-null exactly when the value
 * is). A rotation's commit ([me.raddatz.veyq.core.node.rekey.ScopeRekeyCommit]) rewrites the wraps and never a value, so
 * no rotator can rewrite another member's name, picture or join record.
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

    /** [sealedName]'s value key under the tenant key of its current epoch (`value-key`). */
    @Column(name = "wrapped_name_value_key", length = 128)
    var wrappedNameValueKey: String? = null

    @Column(name = "sealed_avatar")
    var sealedAvatar: String? = null

    /** [sealedAvatar]'s value key under the tenant key of its current epoch (`value-key`). */
    @Column(name = "wrapped_avatar_value_key", length = 128)
    var wrappedAvatarValueKey: String? = null

    @Column(name = "avatar_revision", nullable = false)
    var avatarRevision: Int = 0

    @Column(name = "sealed_join_record")
    var sealedJoinRecord: String? = null

    /** [sealedJoinRecord]'s value key under the tenant key of its current epoch (`value-key`). */
    @Column(name = "wrapped_join_record_value_key", length = 128)
    var wrappedJoinRecordValueKey: String? = null

    /** Who rooted this join (ADR 0111 decision 21): the signer of the invite it was accepted
     *  through, written with [sealedJoinRecord]; null for the tenant's first owner. */
    @Column(name = "join_signer_user_id", columnDefinition = "uuid")
    var joinSignerUserId: String? = null

    /** That owner's endorsement of [sealedJoinRecord] (ADR 0111 decision 21), written by their securing; null until then, for a
     *  record it declared, and whenever a new record replaces the one it endorsed. */
    @Column(name = "join_endorsement")
    var joinEndorsement: String? = null

    /** The revision the wire shows: `0` = no picture, else the counter (which a clear also bumped, ADR 0084). */
    fun wireAvatarRevision(): Int = if (sealedAvatar == null) 0 else avatarRevision

    companion object : PanacheCompanionBase<TenantProfileEntity, String>
}
