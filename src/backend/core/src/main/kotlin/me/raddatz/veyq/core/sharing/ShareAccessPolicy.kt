package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.platform.capabilities.CapabilityIds
import me.raddatz.veyq.core.sharing.ShareEntity

/**
 * The pure share-access decision (ADR 0045 §2, ADR 0031), lifted out of [SharingHandler] so the write-only
 * drop-box boundary is unit-tested and mutation-verified without a Quarkus boot. Given a share's already-loaded
 * role string it decides whether a guest may **read** the sealed gallery ([canView]) or **contribute** an upload
 * ([canUpload]). It holds nothing and fetches nothing — the handler stays the shell that loaded `share.role`.
 *
 * The load-bearing rule — the confidentiality boundary nothing else measures: a `WRITE_ONLY` share is **write-only**, so
 * [canView] is `false` (ADR 0031). This is the server-side enforcement of "an uploader cannot see the gallery or
 * other guests". An unknown/garbage role is denied by both predicates (deny-by-default). The roles are `String`
 * constants on [ShareEntity], not a Kotlin enum, so a mutant that drops a `||`-arm or flips to always-true is only
 * caught by pinning the full 3-role × 2-predicate matrix plus the unknown-role case ([ShareAccessPolicyTest]).
 */
object ShareAccessPolicy {
    /** Roles whose guest resolve serves the sealed gallery (READ/READ_WRITE). WRITE_ONLY is write-only → false. */
    fun canView(role: String) = role == ShareEntity.READ || role == ShareEntity.READ_WRITE

    /** Roles that accept anonymous contributions (identity + upload + delete-own): READ_WRITE/WRITE_ONLY. */
    fun canUpload(role: String) = role == ShareEntity.READ_WRITE || role == ShareEntity.WRITE_ONLY

    /**
     * The instance capability a link of [role] needs (ADR 0032, ADR 0088): one register leaf per role. Not a right of
     * the role but the switch over it, read by both halves of a share switch — publishing a link (create, reactivate,
     * reissue, a role change) and serving one ([ShareGuestGate.activeShare], ADR 0088 decision 5). READ_WRITE and
     * WRITE_ONLY are children of [CapabilityIds.PUBLIC_SHARES], so through the registry's AND along the path they also
     * need it; READ, and any role this build does not know, needs the parent alone.
     */
    fun capabilityFor(role: String): String = when (role) {
        ShareEntity.READ_WRITE -> CapabilityIds.PUBLIC_SHARES_CONTRIBUTE
        ShareEntity.WRITE_ONLY -> CapabilityIds.PUBLIC_SHARES_DROP_BOX
        else -> CapabilityIds.PUBLIC_SHARES
    }
}
