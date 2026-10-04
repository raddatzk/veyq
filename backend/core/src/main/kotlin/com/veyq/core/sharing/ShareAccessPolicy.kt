package com.veyq.core.sharing

import com.veyq.core.sharing.ShareEntity

/**
 * The pure share-access decision (ADR 0045 §2, ADR 0031), lifted out of [SharingHandler] so the write-only
 * drop-box boundary is unit-tested and mutation-verified without a Quarkus boot. Given a share's already-loaded
 * role string it decides whether a guest may **read** the sealed gallery ([canView]) or **contribute** an upload
 * ([canUpload]). It holds nothing and fetches nothing — the handler stays the shell that loaded `share.role`.
 *
 * The load-bearing rule — the confidentiality boundary nothing else measures: an `UPLOADER` is **write-only**, so
 * [canView] is `false` (ADR 0031). This is the server-side enforcement of "an uploader cannot see the gallery or
 * other guests". An unknown/garbage role is denied by both predicates (deny-by-default). The roles are `String`
 * constants on [ShareEntity], not a Kotlin enum, so a mutant that drops a `||`-arm or flips to always-true is only
 * caught by pinning the full 3-role × 2-predicate matrix plus the unknown-role case ([ShareAccessPolicyTest]).
 */
object ShareAccessPolicy {
    /** Roles whose guest resolve serves the sealed gallery (VIEWER/CONTRIBUTOR). UPLOADER is write-only → false. */
    fun canView(role: String) = role == ShareEntity.VIEWER || role == ShareEntity.CONTRIBUTOR

    /** Roles that accept anonymous contributions (identity + upload + delete-own): CONTRIBUTOR/UPLOADER. */
    fun canUpload(role: String) = role == ShareEntity.CONTRIBUTOR || role == ShareEntity.UPLOADER
}
