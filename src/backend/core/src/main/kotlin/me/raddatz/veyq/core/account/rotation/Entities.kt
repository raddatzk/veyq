package me.raddatz.veyq.core.account.rotation

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * An account's one open stage of self-grants (ADR 0111 decision 6): the identity bundle the rotation will
 * publish, and when the stage expires (server-observed, `veyq.account.rotation.stage.ttl` after it opened). Inert —
 * ciphertext and scope ids the server already holds as grant rows — until a Secret-Key-proved publish names it.
 * A second open replaces it; the publish deletes it; [RotationStagePurge] deletes an expired one; the account's erasure
 * cascades to it.
 */
@Entity
@DynamicUpdate
@Table(name = "veyq_rotation_stage")
class RotationStageEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "identity_public_key", nullable = false, columnDefinition = "TEXT")
    lateinit var identityPublicKey: String

    @Column(name = "expires_at", nullable = false)
    lateinit var expiresAt: Instant

    companion object : PanacheCompanionBase<RotationStageEntity, String>
}

/** One staged self-grant: [scopeId]'s epoch-[epoch] key sealed to the stage's identity, signed by it — verified at
 *  upload. Upserted by `(stage, scope)`. */
@Entity
@DynamicUpdate
@Table(name = "veyq_rotation_stage_grant")
class RotationStageGrantEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "stage_id", nullable = false, columnDefinition = "uuid")
    lateinit var stageId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "epoch", nullable = false)
    var epoch: Int = 0

    @Column(name = "wrapped_grant", nullable = false, columnDefinition = "TEXT")
    lateinit var wrappedGrant: String

    @Column(name = "grant_signature", nullable = false, columnDefinition = "TEXT")
    lateinit var grantSignature: String

    companion object : PanacheCompanionBase<RotationStageGrantEntity, String>
}

/** One staged re-signed invite (ADR 0111 decision 20): the stage identity's `invite-key` and
 *  anchor `grant` signatures over the invite at [anchorEpoch], verified at upload — or both null, a declared invite the
 *  publish ends. Upserted by `(stage, invite)`. */
@Entity
@DynamicUpdate
@Table(name = "veyq_rotation_stage_invite")
class RotationStageInviteEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "stage_id", nullable = false, columnDefinition = "uuid")
    lateinit var stageId: String

    @Column(name = "invite_id", nullable = false, columnDefinition = "uuid")
    lateinit var inviteId: String

    @Column(name = "anchor_epoch", nullable = false)
    var anchorEpoch: Int = 0

    @Column(name = "invite_key_signature", columnDefinition = "TEXT")
    var inviteKeySignature: String? = null

    @Column(name = "anchor_grant_signature", columnDefinition = "TEXT")
    var anchorGrantSignature: String? = null

    companion object : PanacheCompanionBase<RotationStageInviteEntity, String>
}

/** One staged join-record endorsement (ADR 0111 decision 21): the stage identity's signature over the member's sealed record,
 *  verified at upload — or null, a declared record — and the SHA-256 of the record it was staged against ([recordDigest],
 *  base64), which the publish compares with the record it finds. Upserted by `(stage, tenant, member)`. */
@Entity
@DynamicUpdate
@Table(name = "veyq_rotation_stage_endorsement")
class RotationStageEndorsementEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "stage_id", nullable = false, columnDefinition = "uuid")
    lateinit var stageId: String

    @Column(name = "tenant_id", nullable = false, columnDefinition = "uuid")
    lateinit var tenantId: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "record_digest", nullable = false, length = 44)
    lateinit var recordDigest: String

    @Column(name = "endorsement", columnDefinition = "TEXT")
    var endorsement: String? = null

    companion object : PanacheCompanionBase<RotationStageEndorsementEntity, String>
}

/**
 * The pending re-key of one scope a publish re-granted (ADR 0111 decision 8): the epoch the scope had then. While the
 * scope's current epoch still equals [epochAtRotation], the account may commit the next one without being an owner
 * (`rotateScope`'s gate), and the scope reads `waiting`. A later rotation of the account replaces its rows. No time is
 * stored: nothing would read it (inbox #318).
 */
@Entity
@DynamicUpdate
@Table(name = "veyq_rotation_regrant")
class RotationRegrantEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    lateinit var id: String

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    lateinit var userId: String

    @Column(name = "scope_id", nullable = false, columnDefinition = "uuid")
    lateinit var scopeId: String

    @Column(name = "epoch_at_rotation", nullable = false)
    var epochAtRotation: Int = 0

    companion object : PanacheCompanionBase<RotationRegrantEntity, String>
}
