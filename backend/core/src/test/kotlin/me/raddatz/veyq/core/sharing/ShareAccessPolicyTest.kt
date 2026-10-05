package me.raddatz.veyq.core.sharing

import me.raddatz.veyq.core.sharing.ShareEntity
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

    // --- canView: serves the sealed gallery (READ/READ_WRITE); WRITE_ONLY is write-only → false ----------------

    @Test
    fun viewerCanView() {
        assertTrue(ShareAccessPolicy.canView(ShareEntity.READ), "a READ share resolves the sealed gallery")
    }

    @Test
    fun contributorCanView() {
        assertTrue(ShareAccessPolicy.canView(ShareEntity.READ_WRITE), "a READ_WRITE share both reads and contributes")
    }

    @Test
    fun uploaderCannotView() {
        assertFalse(
            ShareAccessPolicy.canView(ShareEntity.WRITE_ONLY),
            "a WRITE_ONLY share is a write-only drop-box — it must never read the gallery (ADR 0031)",
        )
    }

    @Test
    fun unknownRoleCannotView() {
        assertFalse(ShareAccessPolicy.canView(unknownRole), "an unrecognised role is denied the gallery (deny-by-default)")
    }

    // --- canUpload: accepts anonymous contributions (READ_WRITE/WRITE_ONLY); a READ share is read-only → false -------

    @Test
    fun viewerCannotUpload() {
        assertFalse(ShareAccessPolicy.canUpload(ShareEntity.READ), "a READ share is read-only — no contributions")
    }

    @Test
    fun contributorCanUpload() {
        assertTrue(ShareAccessPolicy.canUpload(ShareEntity.READ_WRITE), "a READ_WRITE share both reads and contributes")
    }

    @Test
    fun uploaderCanUpload() {
        assertTrue(ShareAccessPolicy.canUpload(ShareEntity.WRITE_ONLY), "a WRITE_ONLY share's whole capability is contributing")
    }

    @Test
    fun unknownRoleCannotUpload() {
        assertFalse(ShareAccessPolicy.canUpload(unknownRole), "an unrecognised role may not contribute (deny-by-default)")
    }
}
