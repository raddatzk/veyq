package com.veyq.app.features.sharing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure share-access decision (ADR 0045 §2, ADR 0031), boot-free and mutation-verified. Every case pins an exact
 * `true`/`false` with a rule-stating message — a bare "not null" would let a mutant that drops a `||`-arm or returns
 * always-true pass (the assertion-strength trap ADR 0045 exists to close). Because the roles are `String` constants
 * (not a Kotlin enum) the matrix is pinned in full: all three roles × both predicates, **plus** an unknown role to
 * document deny-by-default. The end-to-end wiring is the slices `ShareUploaderSliceTest` / `ShareContributorSliceTest`.
 */
class ShareAccessPolicyTest {

    private val unknownRole = "GARBAGE"

    // --- canView: serves the sealed gallery (VIEWER/CONTRIBUTOR); UPLOADER is write-only → false ----------------

    @Test
    fun viewerCanView() {
        assertTrue(ShareAccessPolicy.canView(ShareEntity.VIEWER), "a VIEWER share resolves the sealed gallery")
    }

    @Test
    fun contributorCanView() {
        assertTrue(ShareAccessPolicy.canView(ShareEntity.CONTRIBUTOR), "a CONTRIBUTOR both views and contributes")
    }

    @Test
    fun uploaderCannotView() {
        assertFalse(
            ShareAccessPolicy.canView(ShareEntity.UPLOADER),
            "an UPLOADER is a write-only drop-box — it must never read the gallery (ADR 0031)",
        )
    }

    @Test
    fun unknownRoleCannotView() {
        assertFalse(ShareAccessPolicy.canView(unknownRole), "an unrecognised role is denied the gallery (deny-by-default)")
    }

    // --- canUpload: accepts anonymous contributions (CONTRIBUTOR/UPLOADER); a VIEWER is read-only → false -------

    @Test
    fun viewerCannotUpload() {
        assertFalse(ShareAccessPolicy.canUpload(ShareEntity.VIEWER), "a VIEWER share is read-only — no contributions")
    }

    @Test
    fun contributorCanUpload() {
        assertTrue(ShareAccessPolicy.canUpload(ShareEntity.CONTRIBUTOR), "a CONTRIBUTOR both views and contributes")
    }

    @Test
    fun uploaderCanUpload() {
        assertTrue(ShareAccessPolicy.canUpload(ShareEntity.UPLOADER), "an UPLOADER's whole capability is contributing")
    }

    @Test
    fun unknownRoleCannotUpload() {
        assertFalse(ShareAccessPolicy.canUpload(unknownRole), "an unrecognised role may not contribute (deny-by-default)")
    }
}
