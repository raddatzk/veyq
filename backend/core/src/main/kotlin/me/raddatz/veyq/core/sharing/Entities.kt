package me.raddatz.veyq.core.sharing

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import org.hibernate.annotations.DynamicUpdate

/**
 * A public share (uc.sharing.share-public-viewer, ADR 0029): one Memory of a personal, group or tenant scope
 * (ADR 0029 §6) turned into an anonymous, revocable link `/s/{id}#k=<K_share>`. Operator-blind: the row holds only the
 * opaque 256-bit `id`, the wrap `symWrap(K_share, K_mem)`, the plaintext `role`, the `K_share`-sealed name,
 * and the shared `nodeId` — never `K_share`, `K_mem`, a name, or the viewer. `K_share` rides the URL fragment
 * (ADR 0015) and never reaches the server. Soft (L1) revoke: `enabled = false` / a passed `expiresAt` makes the
 * guest resolve + blob GET `404` (ADR 0029 §7 — already-downloaded ciphertext is not retractable). A deleted share is
 * a tombstone ([deletedAt], `SharingStore.tombstone`): its ciphertext columns are null, and every reader goes through
 * `SharingStore`'s lookups, which never return one.
 */
@Entity
@Table(name = "veyq_share")
@DynamicUpdate
class ShareEntity : PanacheEntityBase {
    /** The opaque 256-bit share id (base64url, unguessable) — half the capability; the other half is the fragment key. */
    @Id
    @Column(name = "id", length = 64)
    lateinit var id: String

    /** The veyq user who owns the share (list/revoke authz) — its creator, or the member who adopted it by re-activation. */
    @Column(name = "owner_account", nullable = false, length = 36)
    lateinit var ownerAccount: String

    /** The shared node's scope — personal, group or tenant (ADR 0029 §6); gates the curator ops and the subtree walk. */
    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    /** The shared Memory (the subtree root). */
    @Column(name = "node_id", nullable = false, length = 36)
    lateinit var nodeId: String

    @Column(name = "role", nullable = false, length = 16)
    lateinit var role: String

    /** `symWrap(K_share, K_mem)` — the Memory's content key sealed under the fresh per-share key (opaque base64).
     *  Null for an UPLOADER share: its guest never holds `K_mem` (ADR 0031 point 7). */
    @Column(name = "wrap")
    var wrap: String? = null

    /** UPLOADER only: the Memory's drop public key sealed under `K_share` (opaque base64) — served instead of [wrap]. */
    @Column(name = "sealed_drop_public_key")
    var sealedDropPublicKey: String? = null

    /** The Memory name re-sealed under `K_share` (opaque base64) — a viewer with only the fragment key shows the title.
     *  Null only on a tombstone. */
    @Column(name = "sealed_name")
    var sealedName: String? = null

    /** False once revoked (soft L1) — the guest resolve + blob GET then `404`. */
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    /** Optional expiry; a share past this instant resolves `404` (same shape as an unknown id). Null = no expiry. */
    @Column(name = "expires_at")
    var expiresAt: Instant? = null

    /** cr-0115 (ADR 0036): the curator note sealed under `K_share` (opaque base64), or null. Shown on the share
     *  page. Since cr-0116 the plaintext is a per-locale JSON map `{ "de": …, "en": … }`. */
    @Column(name = "sealed_note")
    var sealedNote: String? = null

    /** cr-0116 (ADR 0036): the share's optional private label sealed under `K_share` (opaque base64), or null. A
     *  management-only name shown in the curator's share list (falling back to the role); never served to a viewer. */
    @Column(name = "sealed_share_name")
    var sealedShareName: String? = null

    /** ADR 0036: the recoverable per-share key — `K_share` sealed under the scope key `K_SC` at
     *  [sealedShareKeyEpoch] (opaque base64), or null. Lets every scope member recover `K_share` to reveal, edit and
     *  re-copy the share. Never under `K_mem`: every VIEWER/CONTRIBUTOR link carries that key, and a copy under it
     *  would open this share to a sibling link plus a dump (ADR 0036 point 4). */
    @Column(name = "sealed_share_key")
    var sealedShareKey: String? = null

    /** The scope epoch [sealedShareKey] is sealed under — non-null exactly when it is; the scope's current epoch at
     *  create (a stale one is refused `stale_epoch`, ADR 0024 Decision 6). */
    @Column(name = "sealed_share_key_epoch")
    var sealedShareKeyEpoch: Int? = null

    /** The scope's removal counter when this share was minted (ADR 0075 point 4): below the scope's current count, a
     *  member has left since — one who could have rebuilt this link from its recovery copy. Stamped in the mint's
     *  transaction (`SharingStore.persistShare`), never changed. */
    @Column(name = "member_removals_at_mint", nullable = false)
    var memberRemovalsAtMint: Int = 0

    /** Set by a bulk switch-off (ADR 0075 point 5, `SharingStore.turnOffAllInScope`) on every share of the scope, one
     *  already off included: this share comes back only by re-issue, which mints a new row — so the mark never
     *  travels. */
    @Column(name = "reissue_only", nullable = false)
    var reissueOnly: Boolean = false

    /** cr-0118 (ADR 0037): true when the share is password-protected — the guest resolve + blob GET then require a
     *  valid `X-Share-Password` token. An access-control gate, not a crypto one (content stays E2EE via `K_share`). */
    @Column(name = "password_required", nullable = false)
    var passwordRequired: Boolean = false

    /** cr-0118: the salt for the client's `pwToken = Argon2id(password, pw_salt)` (opaque base64), or null. */
    @Column(name = "pw_salt")
    var pwSalt: String? = null

    /** cr-0118: `SHA-256(pwToken)` (opaque base64) — the server-checked verifier, or null. The server never sees the
     *  raw password (the client sends `Argon2id(pw)`); a DB leak still costs an Argon2id-per-guess to use. */
    @Column(name = "pw_verifier")
    var pwVerifier: String? = null

    /** cr-0118: the plaintext password sealed under `K_share` (AAD `share-password`, opaque base64), or null — a
     *  management-only reveal copy a scope member opens with `K_share` recovered from [sealedShareKey] (so under
     *  `K_SC`, which no share link holds — ADR 0036 point 4); never served to a viewer. */
    @Column(name = "sealed_password")
    var sealedPassword: String? = null

    /** ADR 0076: `SHA-256(writeToken)` (opaque base64), where `writeToken = HKDF(K_share, "veyq/v1/share-write:" + id)`
     *  — what a guest naming themself must present the preimage of, so the id alone (a database copy, an access log)
     *  names no guest. Set at create and re-issue for every role; null on a share minted before it existed, which
     *  then takes no new guest, and on a tombstone. */
    @Column(name = "write_verifier")
    var writeVerifier: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    /** Set when the share was deleted (ADR 0029 §7): the row is a tombstone that only holds its id — so no new share
     *  is ever minted under it — and the foreign key its guest identities need. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    companion object : PanacheCompanionBase<ShareEntity, String> {
        const val VIEWER = "VIEWER"
        const val CONTRIBUTOR = "CONTRIBUTOR"

        /** A write-only drop-box (cr-0102, ADR 0031): add photos but the resolve serves no media (`canView` = false). */
        const val UPLOADER = "UPLOADER"
    }
}

/**
 * An anonymous **contributor identity** on a CONTRIBUTOR share (cr-0101, ADR 0030): the display name a visitor
 * gave before contributing, **sealed under `K_mem`** (opaque), plus the hash of an unguessable capability token
 * that authorizes their uploads. Operator-blind: the server never sees the name or the person — only that an
 * opaque identity contributed. The sealed name → identity is the forward-compat hook for buckets.
 */
@Entity
@Table(name = "veyq_share_identity")
@DynamicUpdate
class ShareIdentityEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "share_id", nullable = false, length = 64)
    lateinit var shareId: String

    /** The display name AEAD-sealed (opaque base64) — under `K_mem` for a CONTRIBUTOR identity, under the identity's
     *  own key for an UPLOADER one. */
    @Column(name = "sealed_display_name", nullable = false)
    lateinit var sealedDisplayName: String

    /** UPLOADER identities only: the identity's own key wrapped to the Memory's drop public key (opaque base64). */
    @Column(name = "wrapped_identity_key")
    var wrappedIdentityKey: String? = null

    /** SHA-256 hash of the capability token handed to the client (a DB dump yields no usable token). */
    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ShareIdentityEntity, String>
}

/**
 * The id of a share row the scope purge erased (ADR 0029 §7). The purge must remove share rows — the scope's foreign
 * key requires it — and a freed id could be minted again by anyone holding the dead link's id and `K_share`, serving
 * every other holder of that link their own Memory. The id is 256 random bits and names nothing once its row is gone.
 */
@Entity
@Table(name = "veyq_share_retired_id")
@DynamicUpdate
class ShareRetiredIdEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 64)
    lateinit var id: String

    companion object : PanacheCompanionBase<ShareRetiredIdEntity, String>
}
