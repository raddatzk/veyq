package com.veyq.app.features.sharing

import io.quarkus.hibernate.orm.panache.kotlin.PanacheCompanionBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A public viewer share (uc.sharing.share-public-viewer, cr-0100, ADR 0029): one personal-scope Memory turned
 * into a read-only, anonymous, revocable link `/s/{id}#k=<K_share>`. Operator-blind: the row holds only the
 * opaque 256-bit `id`, the wrap `symWrap(K_share, K_mem)`, the plaintext `role`, the `K_share`-sealed name,
 * and the shared `nodeId` — never `K_share`, `K_mem`, a name, or the viewer. `K_share` rides the URL fragment
 * (ADR 0015) and never reaches the server. Soft (L1) revoke: `enabled = false` / a passed `expiresAt` makes the
 * guest resolve + blob GET `404` (ADR 0029 §7 — already-downloaded ciphertext is not retractable).
 */
@Entity
@Table(name = "veyq_share")
class ShareEntity : PanacheEntityBase {
    /** The opaque 256-bit share id (base64url, unguessable) — half the capability; the other half is the fragment key. */
    @Id
    @Column(name = "id", length = 64)
    lateinit var id: String

    /** The veyq user who created the share (list/revoke authz). */
    @Column(name = "owner_account", nullable = false, length = 36)
    lateinit var ownerAccount: String

    /** The shared node's scope — **personal** only in v1 (ADR 0029 §6); used to gate the subtree walk. */
    @Column(name = "scope_id", nullable = false, length = 36)
    lateinit var scopeId: String

    /** The shared Memory (the subtree root). */
    @Column(name = "node_id", nullable = false, length = 36)
    lateinit var nodeId: String

    @Column(name = "role", nullable = false, length = 16)
    lateinit var role: String

    /** `symWrap(K_share, K_mem)` — the Memory's content key sealed under the fresh per-share key (opaque base64). */
    @Column(name = "wrap", nullable = false)
    lateinit var wrap: String

    /** The Memory name re-sealed under `K_share` (opaque base64) — a viewer with only the fragment key shows the title. */
    @Column(name = "sealed_name", nullable = false)
    lateinit var sealedName: String

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

    /** cr-0115 (ADR 0036): the recoverable per-share key `symWrap(K_mem, K_share)` (opaque base64), or null. Lets the
     *  curator recover `K_share` (they hold `K_mem`) to edit the note in place — openable **only** with `K_mem`. */
    @Column(name = "sealed_share_key")
    var sealedShareKey: String? = null

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
     *  management-only reveal copy the curator opens with a recovered `K_share`; never served to a viewer. */
    @Column(name = "sealed_password")
    var sealedPassword: String? = null

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

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
class ShareIdentityEntity : PanacheEntityBase {
    @Id
    @Column(name = "id", length = 36)
    lateinit var id: String

    @Column(name = "share_id", nullable = false, length = 64)
    lateinit var shareId: String

    /** The contributor's display name AEAD-sealed under `K_mem` (opaque base64). */
    @Column(name = "sealed_display_name", nullable = false)
    lateinit var sealedDisplayName: String

    /** SHA-256 hash of the capability token handed to the client (a DB dump yields no usable token). */
    @Column(name = "token_hash", nullable = false, length = 64)
    lateinit var tokenHash: String

    @Column(name = "created_at", nullable = false)
    lateinit var createdAt: Instant

    companion object : PanacheCompanionBase<ShareIdentityEntity, String>
}
